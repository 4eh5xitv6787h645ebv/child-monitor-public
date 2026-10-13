# Reconnection Test Results

Two emulators (Android 14, SDK 34):
- **emulator-5554** ("child"): runs MonitorService, listening on port 10000.
- **emulator-5556** ("parent"): connects via host port forwarding → `10.0.2.2:10000`.

Port forwarding: `adb -s emulator-5554 forward tcp:10000 tcp:10000`

## Baseline (no fault)
- Parent connect time: **~400 ms** ✓ (was potentially 75-130s with the original `Socket(host, port)`)
- Audio streaming starts immediately ✓
- Child sees parent at 127.0.0.1 (via port forward) ✓

---

## SCENARIO A — Parent airplane mode (12 s blackout)

**Setup**: Parent streaming audio normally. Toggle airplane mode ON for 12 s, then OFF.

### Timeline (parent log)
```
13:19:36.450  WiFi state DISABLING (airplane on)
13:19:37.523  Audio streaming failed (SocketException "Software caused connection abort")
13:19:37.940  Connection lost log
13:19:40.319  Connect attempt → ENETUNREACH after **3000ms** ✓ (C1 fix: connect timeout)
13:19:44.998  Waiting 8s (backoff growing 2→4→8s)
13:19:48.534  WiFi ENABLING (airplane off)
13:19:52.165  ⚡ Network restored signal received — kicking reconnect loop awake ✓ (C5 fix)
13:19:52.272  ⚡ Backoff interrupted by network-restored signal — retrying immediately ✓
13:19:52.302  Connect attempt 1 (reset from 4!) ✓ (H1 fix)
13:19:52.503  Successfully connected — Connect time: 51ms
```

**Reconnect latency after network restore: ~340 ms** (vs. up to 60 s on old code) — **>175x improvement** for this scenario.

### Bug found during test
**REGRESSION-1** (introduced by my C5 fix and immediately fixed):
At 13:19:55, OS reported a second network-available event when Cellular→WiFi handover happened. My initial implementation of `onNetworkRestored` always closed `activeSocket` to break out of a stuck connect. **Result**: a healthy in-flight stream was killed by an OS interface change.

**Fix applied** to `ListenService.onNetworkRestored`:
```kotlin
if (!isAudioPlaying) {
    try { activeSocket?.close() } catch (_: Exception) {}
}
```
Only kick the socket if we're not currently receiving audio.

---
## Test runs follow…

---
## SCENARIO B — Long blackout (40 s)

**Setup**: Parent streaming. Toggle airplane mode ON for 40s.

### Backoff trajectory (proves H1 fix + C5 fix work)
```
13:32:29  connection lost
13:32:29  Waiting 2s   (attempt 2)
13:32:31  attempt → 4s wait (attempt 3)
13:32:35  attempt → 8s wait (attempt 4)
13:32:43  attempt → 16s wait (attempt 5)
13:33:00  attempt → 32s wait (attempt 6)
13:33:11  ⚡ Network restored signal — Backoff interrupted
13:33:11  Connect attempt 1 (RESET from 6!) → Successfully connected, Connect time: 15ms
```
**Reconnect after network returned: ~200 ms.** Backoff was at 32 s when the OS reported wifi available. The signal-interrupt fix cut it from "wait ~20 s more" to "retry now."

---
## SCENARIO C — Child app force-stop / restart (15 s outage)

**Setup**: Parent streaming. Force-stop child app for 15 s, restart it.

```
13:34:10  connection died (read=-1)
13:34:11  Waiting 2s
13:34:13–19  several connect-then-close cycles (host-port adb-forward artifact — see note below)
13:34:19  Child app fully back; parent connected, audio resumed
```
**Total recovery: ~9 s** (mostly child app restart time).

### Test infrastructure note
With adb-forward `host:10000 → child-emu:10000`, when the child app dies the host port still ACCEPTs the TCP handshake (adb's kernel-level forwarder) and immediately closes — so the parent sees rapid connect/disconnect cycles instead of `ConnectException`. In a real LAN, the parent would get RST/CONNECT_REFUSED on each attempt. The fix `reconnectAttempts` only resets after a ≥2 s connection guards against this thrash (added during testing).

---
## What the fixes prove

| Fix | Test that validated it | Outcome |
|---|---|---|
| C1: explicit `Socket().connect(addr, 3000ms)` | Scenario A — `ENETUNREACH after 3000ms` log line | ✓ 3s connect timeout, not 75-130s |
| C5: `NetworkStateMonitor.onAvailable` wakes backoff | Scenario A & B — "Backoff interrupted by network-restored signal" | ✓ Sub-second reconnect after network back |
| H1: reset `reconnectAttempts=0` on network restored | Scenario B — "Connect attempt 1" right after 32s sleep | ✓ Backoff doesn't keep growing across network swaps |
| Quick-disconnect detect (≥2s before reset) | Scenario C — backoff grows through the connect-then-close storm | ✓ No fast-cycle thrash |
| NSD duplicate refresh on startup | Child log post-fix: only ONE "NSD service registered successfully" | ✓ No "(2)" suffix in mDNS listing |
| onNetworkRestored guards against killing live stream | Scenario A re-run — cellular→WiFi handoff did NOT close active socket | ✓ Active stream survives transport change |

---
## Bug found during iterative review

### REGRESSION-2 (from second-pass review, fixed before retest)
**CRITICAL-1 & CRITICAL-2** in `MonitorService.kt`: My new `pruneStaleConnections` + the audio-loop break path could race `audioRecord.release()` between heartbeat thread and audio thread → double-release crash. Fixed by adding `audioRecordLock` synchronizing `startAudioRecording`/`stopAudioRecording`/teardown.

**HIGH-1** in `ListenService.kt`: `onDestroy` interrupted the listen thread but didn't close `activeSocket`. `interrupt()` cannot unblock a blocking `read()` — service could linger 8 s. Fixed by closing `activeSocket` before `interrupt()`.
