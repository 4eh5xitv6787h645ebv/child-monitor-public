# Child Monitor — Reconnection Audit

Generated during extensive reconnection testing/review.

## Severity Legend
- **CRITICAL**: Causes reconnection failure or multi-minute delays.
- **HIGH**: Slows reconnection or causes silent state corruption.
- **MEDIUM**: Edge-case bugs / annoyances.
- **LOW**: Cosmetic / minor races.

---

## CRITICAL

### C1. `Socket(host, port)` blocks ~75-130s on dead routes (NO connect timeout)
**File**: `ListenService.kt:545`
```kotlin
val socket = Socket(address, port)  // no connect timeout!
```
The 2-arg `Socket` constructor performs blocking DNS + TCP connect with OS default timeout (~2 min on Android). `socket.soTimeout` is only the *read* timeout. A dead IP in history hangs the reconnect loop for ~2 minutes per entry.

**Fix**:
```kotlin
val socket = Socket()
socket.connect(InetSocketAddress(address, port), 3000)  // 3s connect timeout
socket.soTimeout = audioReadTimeoutMs
```

### C2. `tryNextIpInHistory` timeout-Handler race
**File**: `ListenActivity.kt:961-981`
The 3s timeout Handler compares `currentReconnectIp == ipPort`. With duplicates / wrap-around / quick history, stale timeouts can fire after we've advanced, double-incrementing `reconnectIndex` and skipping devices. Handler callbacks aren't cancelled on cancel/next-step, so they can fire after `cancelReconnect`.

**Fix**: Use token-removable callbacks; cancel pending timeout when advancing.

### C3. Service stop-then-bind race in `tryNextIpInHistory`
**File**: `ListenActivity.kt:957`
`doUnbindAndStopService()` (default `stopService=true`) destroys the service every IP attempt. `stopService()` is async; old `Socket()` connect (per C1) may still be blocking. Old service fires `onError` *after* new service started, killing a successful reconnect.

**Fix**: Pass new address to running service (`updateTarget(addr, port)` binder method), don't tear down per attempt.

### C4. `hasTriedAutoReconnect` set once, never reset
**File**: `ListenActivity.kt:78, 239-246`
After first disconnect, auto-reconnect fires once. On subsequent disconnects, flag stays `true`, auto-reconnect never fires again until user manually relaunches Activity.

**Fix**: Reset to `false` in `onReconnectSuccess()` and `cancelReconnect()`.

### C5. `NetworkStateMonitor.onAvailable` does NOT interrupt backoff sleep
**File**: `NetworkStateMonitor.kt:35-45`, `ListenService.kt:631-647`
`onAvailable` only logs. If wifi returns at second 5 of a 60s `Thread.sleep`, parent waits the full 60s before retrying.

**Fix**: Pipe `onAvailable` into `ListenService`: reset `reconnectAttempts=0`, `listenThread?.interrupt()`.

---

## HIGH

### H1. Exponential backoff doesn't reset on network change
**File**: `ListenService.kt:631-635`
`reconnectAttempts` only resets on successful connect. After wifi swap, count persists — sleeping 60s between attempts on a brand-new network.

### H2. `audioReadTimeoutMs = 20s` too high for safety-critical audio
**File**: `ListenService.kt:70`
A silent dead connection takes 20s + connect attempt to detect. Combined with C1 → up to 2 min recovery from silent disconnect.
**Fix**: Drop to 8-10s.

### H3. `listenThread?.interrupt()` does NOT unblock `Socket()` connect
**File**: `ListenService.kt:526-654`
`Thread.interrupt()` cannot cancel a blocking `Socket(addr, port)` constructor. Old thread keeps running for up to 2 minutes while a new thread also tries. Now two threads race to open sockets, both mutate `reconnectAttempts`, both fire callbacks.
**Fix**: Coupled to C1. With explicit connect timeout, sockets fail fast; also save socket reference and `socket.close()` from outside to force-cancel.

### H4. Health check interval (5 min) = no-data threshold (5 min)
**File**: `ListenService.kt:71, 657-674`
Health check runs every 5 min and uses same value as threshold. Worst case ~10 min before reconnect triggered.
**Fix**: Check every 30s with 60-90s no-data threshold.

### H5. `awaitingConnectionResult` / `currentReconnectIp` not `@Volatile`
**File**: `ListenActivity.kt:74-75`
Read on multiple threads (listen thread → onError → runOnUiThread; main; snapshot thread indirectly). Visibility races.

### H6. Service callbacks not nulled on Activity onDestroy → leak
**File**: `ListenActivity.kt:393-409`, `ListenService.kt:522-524`
Activity destroys but service keeps lambdas capturing the dead Activity (since `doUnbindAndStopService(stopService=false)`). Whole view tree pinned. Memory leak per rotation/back-press.
**Fix**: `listenService?.onError = null; onUpdate = null; onConnected = null` in `onDestroy`.

### H7. NSD discovery re-entrant Handler not cancelled on destroy
**File**: `ListenActivity.kt:1123, 1140`
`postDelayed({ reconnectToLast() }, 5000)` inside discovery callbacks lacks a removable token. `isFinishing` check at 1124/1141 catches user-finish, NOT config change destroy.

### H8. `tryNextIpInHistory` tears down service per attempt
**File**: `ListenActivity.kt:957`
`doUnbindAndStopService()` kills service onDestroy → wake lock released, audio focus dropped, heartbeat stopped. Device may sleep between attempts. Notification flicker.

### H9. NSD re-registration creates 500ms discovery blackhole every 2 minutes
**File**: `MonitorService.kt:582-606`
Unregister → `Thread.sleep(500)` → re-register. Parents discovering during this window get no answer. Likely unnecessary — Android's mDNS handles TTL refresh autonomously.
**Fix**: Delete the loop. Re-register only on `onRegistrationFailed` and on `NetworkStateMonitor.onAvailable` (IP changed).

---

## MEDIUM

### M1. `reconnectDelayMs = 3000L` < socket connect timeout (per C1)
**File**: `ListenActivity.kt:73`
UI gives up after 3s, but `Socket()` may still be connecting. Whole IP list cycles every ~30s with no IP getting a fair shot. Coupled to C1.

### M2. `connectedIpHistory.split(",")` produces empty strings on trailing commas
**File**: `ListenActivity.kt:811-817`
Fix: `.split(",").filter { it.isNotBlank() }`.

### M3. Service-level alert sound plays on every retry
**File**: `ListenService.kt:594, 615`
UI mutes alerts in `reconnectToLast` but service-level retry loop plays alert on every attempt. After wifi blip: alert→retry→alert→retry until reconnected.

### M4. TCP keepalive enabled but Android default idle is 2 hours
**File**: `ListenService.kt:548`
`socket.keepAlive = true` is decorative — actual keepalive idle is ~7200s on Linux/Android.

### M5. `startForegroundService` + `bindService` race during rapid reconnect cycles
**File**: `ListenActivity.kt:188-209`
Service must call `startForeground()` within 5s of `startForegroundService` to avoid ANR. Rapid stop/start during reconnect can race.

---

## LOW

### L1. `totalDisconnections` Int mutated without lock from multiple threads
`ListenService.kt:103-106, 604, 619, 373`

### L2. `audioPriorityPressureStreak` etc. mutated from snapshot thread
`ListenActivity.kt:680-686, 636-706` (via `recomputeSnapshotInterval`)

### L3. `connectedIpHistory` mutated from NSD callback thread (not main)
`ListenActivity.kt:66, 1089-1093`

### L4. `connectedIpHistory.joinToString(",")` breaks on IPv6 (contains colons)
Use bracketed notation `[::1]:10000` or JSON serialization.

### L5. `recentDisconnectReasons` synchronized but lock object is the list itself
`ListenService.kt:938-947` — safer to use a dedicated lock object.

---

## Reconnect-Time Budget (current vs target)

| Scenario | Current Worst Case | Target |
|---|---|---|
| Child app crash, same IP | ~2.5 min | ~13s |
| Wifi off→on during streaming | ~80s | ~8s |
| Child IP changed (new wifi), N=10 saved | 20+ min | ~65s |
| Auto-reconnect after multi-failure | **NEVER fires after first** (C4) | Always |

---

---

# Fixes Applied (by code section)

## ListenService.kt
- ✅ **C1**: `Socket()` + `connect(addr, 3000)` — fast-fail dead routes (was OS-default ~75-130s)
- ✅ **H2**: `audioReadTimeoutMs` 20s → 8s
- ✅ **H4**: Decoupled `healthCheckInterval` (30s) from `noDataThresholdMs` (60s)
- ✅ **H3 partial**: Health check now closes `activeSocket` to force-cancel hung reads
- ✅ **C5 + H1**: `onNetworkRestored()` callback wired to `NetworkStateMonitor.onNetworkAvailable` — resets `reconnectAttempts=0` and signals backoff sleep to break out immediately
- ✅ **Regression fix**: `onNetworkRestored` only closes socket if `!isAudioPlaying` (so live streams aren't killed on cellular→WiFi handoff)
- ✅ **L1**: `shouldReconnect`, `reconnectAttempts`, `lastAudioDataTime` made `@Volatile`
- ✅ **Quick-disconnect**: `reconnectAttempts=0` reset only after connection lasted ≥2s (prevents fast-cycle thrash)
- ✅ **HIGH-1 from 2nd-pass review**: `onDestroy` closes `activeSocket` before interrupt (was leaking up to 8s)
- ✅ **Audio resource cleanup**: Added `audioTrack.release()` in stream finally; `try` around stop/close
- ✅ Skip first network-available callback (cosmetic — avoids redundant initial refresh)

## ListenActivity.kt
- ✅ **C4**: Reset `hasTriedAutoReconnect = false` in `onReconnectSuccess` and `cancelReconnect`
- ✅ **H5**: `isReconnecting`, `awaitingConnectionResult`, `currentReconnectIp` made `@Volatile`
- ✅ **H6**: Null `listenService.onError/onUpdate/onConnected` in `onDestroy` (was leaking Activity)
- ✅ **H7**: All reconnect callbacks routed through a single `reconnectHandler` so `onDestroy` can cancel them all
- ✅ **C2**: `pendingPerIpTimeout: Runnable?` field lets us cancel the per-IP timeout when advancing. Timeout guard now checks both `currentReconnectIp` AND `reconnectIndex` to prevent stale-fire double-skip.
- ✅ **M2**: Filter blank IPs in history split (`.filter { it.isNotBlank() }`)
- ✅ **H8 partial**: `tryNextIpInHistory` delay reduced from 500ms→250ms each step (still tears down service per attempt, full refactor deferred)

## MonitorService.kt
- ✅ **S-H1**: `pruneStaleConnections` now uses `sendUrgentData(0xFF)` probe for connections idle >90s (matches CLAUDE.md docs that previously contradicted the code)
- ✅ **S-C1**: Monitor thread self-restart from heartbeat (was permanently dead on ServerSocket bind failure)
- ✅ **S-C1 bonus**: `ensureMonitorThread` retries `ServerSocket(port)` with exponential backoff (was single-shot)
- ✅ **S-C3**: `onRegistrationFailed` nulls `registrationListener` so heartbeat re-registers (was permanently breaking NSD)
- ✅ **S-H4**: NSD re-registration triggered from `NetworkStateMonitor.onNetworkAvailable` (was waiting up to 2 min)
- ✅ **S-H7**: `isRecording` made `@Volatile`
- ✅ **S-H6**: `bytesSent`, `totalBytesSent`, `audioPacketsSent` replaced with `AtomicLong`
- ✅ **S-H5**: `maxParents = 8` cap (was unbounded — thread-explosion DoS risk)
- ✅ **S-M2**: `ParentConnection` is no longer a data class (data-class equality with mutable `bytesSent` was buggy); removal uses socket identity (`===`)
- ✅ **S-H2 / S-M6**: Moved `startAudioRecording` outside synchronized block; replaced `Thread.sleep(1000)` busy-wait with blocking `inputStream.read()`
- ✅ **S-M4**: Try/catch around `socket.keepAlive=true; tcpNoDelay=true` to prevent leak on flag-setup failure
- ✅ **2nd-pass CRITICAL fix**: Added `audioRecordLock` synchronizing `startAudioRecording`/`stopAudioRecording`/teardown to prevent double-release race between heartbeat-prune and audio thread teardown
- ✅ Skip first network-available callback (cosmetic — avoids duplicate "(2)" NSD registration on startup)

## NetworkStateMonitor.kt
- ✅ Added public `onNetworkAvailable: (() -> Unit)?` callback with 1500ms debounce
- ✅ Wrapped callback invocation in try/catch so subscriber errors don't kill monitoring

---

# Validated on emulators (scenarios A, B, C)
See `RECONNECTION_TEST_RESULTS.md` — measured improvements:
- Network outage 12s: parent reconnects **340ms** after network restored (was unbounded; could be 60s+)
- Network outage 40s: backoff capped at 32s, **signal interrupt resumes in ~200ms** after wifi return
- Child app crash + restart (15s): full audio recovery in **~9s** (mostly child boot)
- TCP connect timeout: **3000ms hard ceiling** confirmed in log (`ENETUNREACH after 3000ms`)

---

# Server-Side (MonitorService) Bugs

## CRITICAL (Server)

### S-C1. Monitor thread exits permanently on `ServerSocket()` failure
**File**: `MonitorService.kt:957-1014`
If port 10000 bind fails (TIME_WAIT after restart, transient WiFi error), the catch logs "CRITICAL" but thread dies. `ensureMonitorThread()` is only called from `onStartCommand` — once the service is up, nothing re-creates the accept thread. **Result**: child appears running (notification visible) but is invisible to all parents. Fatal for unattended baby monitor.
**Fix**: Retry with backoff inside ensureMonitorThread; auto-restart from heartbeat if `monitorThread == null || !isAlive`. Optionally fall back to ephemeral port.

### S-C2. NSD refresh 500ms unregister window + listener race
**File**: `MonitorService.kt:582-606`
`unregisterService → Thread.sleep(500) → registerService` is racing the async unregister callback. New listener gets set immediately, but old unregister may complete after — leaving listener IDs colliding in NSD's internal map. On slow devices may even register a listener that never gets `onServiceRegistered`. Plus 500ms blackhole every 2 minutes for fresh discoveries.
**Fix**: Delete the periodic refresh; rely on Android mDNS TTL. Re-register only on `onRegistrationFailed` and on `NetworkStateMonitor.onAvailable` (IP changed).

### S-C3. `onRegistrationFailed` never retries → permanent NSD breakage
**File**: `MonitorService.kt:1054-1061`
Only logs. After any transient registration failure (commonly `FAILURE_ALREADY_ACTIVE` from refresh race), `registrationListener` is non-null but service NOT advertised. Next `unregisterService` throws `IllegalArgumentException`.
**Fix**: Null listener, schedule retry with backoff, surface to UI.

---

## HIGH (Server)

### S-H1. `pruneStaleConnections` does NOT use `sendUrgentData` — contradicts CLAUDE.md docs!
**File**: `MonitorService.kt:613-646` vs `CLAUDE.md:118`
CLAUDE.md explicitly documents: *"Stale Connection Detection: MonitorService.pruneStaleConnections() runs every heartbeat cycle and uses `socket.sendUrgentData(0xFF)` to probe connection health proactively"*. The implementation does NOT do this. It only reads socket-state flags which remain "connected" until next write fails. With audio recording paused (talkback active, no parents writing), dead parents persist indefinitely.

### S-H2. Race: `startAudioRecording` from inside synchronized add → audio thread iterating non-synchronized
**File**: `MonitorService.kt:132-147, 241-366`
Audio thread iterates `connectedParents` outside any sync block; cleanup re-acquires sync. `stopAudioRecording` called from inside audio thread (line 311) does `audioRecordThread?.interrupt()` — self-interrupt. Restart racing with new parent's first-connect.

### S-H3. `ServerSocket.accept()` not interruptible via `Thread.interrupt()`
**File**: `MonitorService.kt:974-999, 1163`
`accept()` only exits on `socket.close()`. `onDestroy` interrupts first then closes — works, but rapid stop/start can leave new thread skipped via `isAlive` check.

### S-H4. WiFi IP change doesn't rebind ServerSocket or update NSD
**File**: `MonitorService.kt:960, 1019-1081`
`ServerSocket(port)` binds to 0.0.0.0 so it survives IP changes. But NSD advertisement may still point at old IP until next 2-minute refresh (which has its own race per S-C2). NetworkStateMonitor never triggers re-advertise.

### S-H5. Thread-per-parent unbounded
**File**: `MonitorService.kt:990-993`
No max parents, no rate limit, no thread pool. Misbehaving parent can OOM the child.

### S-H6. `bytesSent`, `totalBytesSent`, `audioPacketsSent` not atomic
**File**: `MonitorService.kt:79-80, 119, 272-288, 913`
Plain `var Long` mutated from audio thread, read from web/status thread.

### S-H7. `isRecording` not `@Volatile`
**File**: `MonitorService.kt:71`
JIT may cache the value indefinitely; audio thread may never see stop signal.

---

## MEDIUM (Server)

### S-M1. No max-retry on audio recorder restart → infinite loop on permanent failure
`MonitorService.kt:355-362`

### S-M2. `connectedParents.removeAll(deadConnections)` uses data-class equality (includes mutable `bytesSent`)
`MonitorService.kt:297, 630` — fragile. Use socket identity.

### S-M3. `refreshNsdRegistration` race with `onDestroy.unregisterService`
`MonitorService.kt:582, 1083-1089, 1127`

### S-M4. `accept()` socket flag-setup throws without close → leak
`MonitorService.kt:978-980`

### S-M5. Hardcoded port 10000, no fallback
`MonitorService.kt:474`

### S-M6. `Thread.sleep(1000)` busy-wait per parent connection
`MonitorService.kt:151-153` — replace with blocking read for EOF.

---

## Architectural Top 5 (ROI ranked)
1. **TCP connect timeout + parallel IP-list probing** — biggest single win (2-8 min → 3-5s).
2. **Wire `NetworkStateMonitor.onAvailable` → reconnect kick** — airplane-mode return drops 60-130s → <5s.
3. **End the service vs UI loop fight** — single owner of reconnection state, no service teardown per attempt.
4. **Delete the 2-minute NSD re-registration loop** — eliminate the discovery blackhole.
5. **Typed `EndpointHistory` record** — IPv6, recency-by-SSID, success metadata.
