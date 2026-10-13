# Child Monitor — Reconnection Reliability Pass

## Overview
Multi-pass audit + targeted fixes to the parent↔child reconnect logic.

- 3 parallel review agents combed the parent service (`ListenService`), parent UI (`ListenActivity`), child service (`MonitorService`), discovery (`NsdDiscoveryHelper`), and network monitoring (`NetworkStateMonitor`).
- Identified **3 CRITICAL** parent-side, **3 CRITICAL** child-side, ~15 HIGH, ~10 MEDIUM, and several LOW issues.
- Two emulators (Android 14) used for end-to-end validation with port forwarding `host:10000 ↔ child-emu:10000`.
- One regression I introduced during fixes was caught and corrected.
- A second-pass review agent found **2 new CRITICAL races** (audio-record lifecycle) — fixed.

## Headline reconnect time improvements

| Scenario | Before fixes | After fixes | Why |
|---|---|---|---|
| Dead-IP in history hangs reconnect | up to **75–130s per IP** | **3s per IP** | Explicit `Socket().connect(addr, 3000ms)` |
| Wifi back after airplane mode (parent) | up to **+60s** sleep | **~340ms** | `NetworkStateMonitor.onAvailable` interrupts backoff sleep |
| Wifi back after long blackout (40s+) | could be **>1min more** | **~200ms** | Backoff sleep polls a wake signal every 200ms |
| Auto-reconnect after 2nd disconnect | **never fires** | works every time | `hasTriedAutoReconnect` reset on success/cancel |
| Child IP change | up to **2 minutes** | **~immediate** | NSD re-registered on `onNetworkAvailable` |
| Child app dies | dead forever if port bind fails on restart | **self-restart** via heartbeat | Monitor thread auto-restart, ServerSocket retry |
| Active stream during cellular→WiFi handoff | killed by network callback (regression I introduced) | survives | Guard `if (!isAudioPlaying) close()` |

## Robustness improvements not measurable as latency

- Activity leak (whole view tree pinned via service callbacks) — fixed by nulling `onError/onUpdate/onConnected` in `onDestroy`.
- Service callbacks fire onto destroyed Activity — fixed.
- TCP urgent-data probe (`sendUrgentData(0xFF)`) actually present in `pruneStaleConnections` (CLAUDE.md docs promised it; code didn't have it).
- Idle parents (no successful write in 90s) now pruned regardless of audio recording state.
- Data-class equality bug on `ParentConnection` (mutable `bytesSent` in `removeAll`) — replaced with socket-identity.
- `AudioRecord` lifecycle race (double-release crash potential between heartbeat-prune and audio-thread teardown) — `audioRecordLock` synchronization.
- `maxParents=8` cap prevents thread-explosion DoS from misbehaving parent.
- `@Volatile` / `AtomicLong` / `AtomicBoolean` applied to fields read from multiple threads.
- `BindException` on port 10000 (TIME_WAIT after restart) is no longer permanent — bind retries with backoff.
- `onRegistrationFailed` no longer permanently breaks NSD — clears listener for retry.
- Per-IP timeout in `tryNextIpInHistory` is now token-removable; guards check both ip AND index to prevent stale-fire double-skip.

## Files modified

- `app/src/main/kotlin/de/rochefort/childmonitor/ListenService.kt`
- `app/src/main/kotlin/de/rochefort/childmonitor/ListenActivity.kt`
- `app/src/main/kotlin/de/rochefort/childmonitor/MonitorService.kt`
- `app/src/main/kotlin/de/rochefort/childmonitor/NetworkStateMonitor.kt`

## Documents produced

- `RECONNECTION_BUGS.md` — full bug catalog with file:line, severity, repro, fixes
- `RECONNECTION_TEST_RESULTS.md` — emulator test logs with measured latencies
- `RECONNECTION_SUMMARY.md` — this file

## Outstanding (lower priority, deferred)

- IP history is `List<String>` of `"ip:port"` — breaks on IPv6 (colons in address). Would benefit from typed `Endpoint` record with `addressFamily`, `lastSuccessTs`, `seenSsid` for smarter retry ordering.
- `tryNextIpInHistory` still calls `doUnbindAndStopService()` per attempt — better to push new target to running service via a binder method. Currently the per-attempt teardown costs ~500ms + wake lock churn.
- Service-level `playAlert()` plays on every retry while UI-level mutes alerts only during the UI reconnect loop. Inconsistent UX.
- `reconnectAttempts` is `@Volatile Int` but `++` is not atomic — make it `AtomicInteger` for consistency with the AtomicLong upgrades in `MonitorService` (per 2nd-pass review MEDIUM-1).
- Health-check `lastAudioDataTime` is now `@Volatile Long` but `MEDIUM-2` flagged a potential torn-read on 32-bit ARM (legacy SDK 21 devices). Resolved by adding `@Volatile`.
- The 2-minute NSD re-registration loop in `MonitorService` could likely be deleted entirely once `onNetworkAvailable`-triggered re-registration is proven in the field. Currently kept for safety, but it's the architecturally-cleaner removal.