# Child Monitor - Claude Development Guide

## Project Overview

Open-source Android baby monitor app (GPLv3). Two-device architecture:
- **Child device**: Runs `MonitorService` (foreground) - captures audio/video, streams to parents
- **Parent device**: Runs `ListenService` (foreground) - receives audio, displays camera snapshots, talkback

**Package**: `de.rochefort.childmonitor`
**Min SDK**: 34 (Android 14) | **Target SDK**: 34 (Android 14)
**Language**: Kotlin | **Build**: Gradle 9.5.0

## Build & Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Install on specific device
adb -s <SERIAL> install -r -d app/build/outputs/apk/debug/app-debug.apk

# Run tests
./gradlew testReleaseUnitTest

# Run lint
./gradlew lintRelease
```

## Test Devices

| Device | Serial | Role | SDK |
|--------|--------|------|-----|
| Android 14+ phone | Supply via adb | Child | 34+ |
| Android 14+ phones | Supply via adb | Parents | 34+ |

## Pulling App Diagnostic Logs

The app writes its own logs via `LogManager` to internal storage (separate from logcat):

```bash
# List log files
adb -s <SERIAL> shell "run-as de.rochefort.childmonitor ls -la files/diagnostic_logs/"

# Pull specific day's log
adb -s <SERIAL> shell "run-as de.rochefort.childmonitor cat files/diagnostic_logs/log_YYYY-MM-DD.txt" > output.txt

# Pull system logcat filtered to app
adb -s <SERIAL> logcat -d | grep -i "childmonitor\|child.monitor\|de.rochefort"
```

Log format: `[TIMESTAMP] [LEVEL] [CATEGORY] [DEVICE_TYPE] Message`
Categories: CONNECTION, NETWORK, AUDIO, SYSTEM, DISCOVERY, HEALTH

## Architecture

### Network Topology
```
Child Device (port 10000)     Parent Device(s)
  MonitorService              ListenService
    |-- ServerSocket          |-- Socket connect
    |-- AudioRecord (mic)     |-- AudioTrack (speaker)
    |-- CameraStreamer        |-- Snapshot fetcher (HTTP)
    |-- WebControlServer      |-- Talkback (WebSocket)
    |   (port 11000)          |
    |   /snapshot (JPEG)      |
    |   /api/status (JSON)    |
    |   /ws/audio (WS)        |
    |   /ws/talkback (WS)     |
    |-- NSD registration      |-- NSD discovery
```

### Audio Pipeline
- Codec: G.711 mu-law (8kHz mono, 16-bit PCM -> 8-bit mu-law)
- Child records via `AudioRecord`, encodes with `G711UCodec`, sends raw over TCP socket
- Parent decodes with `G711UCodec` (via `AudioCodecDefines.CODEC`), plays via `AudioTrack`
- Talkback: Parent records -> mu-law encode -> WebSocket binary -> Child decodes -> `AudioTrack`

### Key Source Files

| File | Lines | Purpose |
|------|-------|---------|
| `MonitorService.kt` | ~980 | Child foreground service - audio capture, multi-parent streaming, camera, web server |
| `ListenService.kt` | ~830 | Parent foreground service - audio playback, auto-reconnect, health checks |
| `ListenActivity.kt` | ~1050 | Parent UI - connection, snapshots, talkback, reconnect loop |
| `WebControlServer.kt` | ~370 | NanoHTTPD server on child (REST + WebSocket) |
| `CameraStreamer.kt` | ~235 | CameraX snapshot capture with exposure boost |
| `StartActivity.kt` | ~175 | Role selection (child/parent) |
| `MonitorActivity.kt` | ~195 | Child device UI |
| `DiscoverActivity.kt` | ~235 | Parent mDNS discovery / manual IP entry |
| `LogManager.kt` | ~280 | Centralized diagnostic logging |
| `NetworkStateMonitor.kt` | ~305 | WiFi/network state tracking |
| `BatteryStateMonitor.kt` | ~250 | Battery, Doze mode, charging state |
| `NsdDiscoveryHelper.kt` | ~200 | mDNS discovery wrapper |

### Multi-Parent Support
`MonitorService` uses `CopyOnWriteArrayList<ParentConnection>` to track all connected parents. Audio is broadcast to all simultaneously. Dead connections are detected on write failure and pruned.

## Known Quirks & Gotchas

### CameraStreamer Lifecycle
`CameraStreamer` manages its own `LifecycleRegistry`. The registry CANNOT transition from DESTROYED back to CREATED. When restarting, a fresh `LifecycleRegistry` is created. If you change camera logic, ensure `stop()` -> `start()` cycles work correctly.

### Audio Focus
Uses `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so other media apps (YouTube etc.) can play at reduced volume alongside the monitor. The app does NOT re-request focus when lost - it keeps playing regardless since baby monitoring is safety-critical. AudioTrack continues playing even without focus.

### Reconnection Architecture
Two independent reconnection mechanisms:
1. **Service-level** (`ListenService.doListen`): Endless `while(shouldReconnect)` loop with exponential backoff (capped at 60s). Backoff shift capped at 5 to prevent integer overflow.
2. **UI-level** (`ListenActivity.reconnectToLast`): Cycles through saved IP history endlessly (`maxReconnectLoops=0`). Falls back to NSD discovery periodically, then restarts IP loop. Alerts are muted during reconnection to avoid disconnect sounds.

### WebSocket Client Framing
The talkback push-to-talk uses a raw TCP socket with manual WebSocket framing (not a WS library). Per RFC 6455, client-to-server frames MUST be masked (4-byte XOR mask). Payloads >= 126 bytes need extended length encoding. See `ListenActivity.sendWebSocketFrame()`.

### Android Power Management
The child device's heartbeat shows gaps of 15-25 minutes (expected: 5 min) due to Android Doze mode throttling, even as a foreground service. The camera can enter a "zombie" state (running=true, hasFrame=false) after extended runtime. A health check in the heartbeat loop auto-restarts it.

### Stale Connection Detection
`MonitorService.pruneStaleConnections()` runs every heartbeat cycle and uses `socket.sendUrgentData(0xFF)` to probe connection health proactively, rather than only detecting dead connections on audio write failure.

## Bug Fix History (Feb 2026)

### Issue #1: Talkback not working
**Root cause**: WebSocket frames were not masked (RFC 6455 violation). NanoWSD silently rejects unmasked client frames.
**Fix**: Added proper masking + extended payload length to `sendWebSocketFrame()`.

### Issue #2: Reconnect not closing on back press
**Root cause**: `onDestroy()` didn't cancel reconnect loop. Handler callbacks kept firing. Alert sounds played on each failed attempt.
**Fix**: Cancel reconnect + remove all Handler callbacks in `onDestroy()`. Mute alerts during reconnection, restore preference on completion.

### Issue #3: YouTube/video paused by app
**Root cause**: `AUDIOFOCUS_GAIN` (exclusive) forced other apps to stop. App re-requested focus on every loss.
**Fix**: Changed to `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Removed aggressive focus re-request. App keeps playing regardless of focus state.

### Issue #4: Disconnects after long runtime + camera dies
**Root causes**: (1) CameraStreamer lifecycle went DESTROYED and couldn't restart. (2) Dead parent connections only detected on audio write failure. (3) Exponential backoff integer overflow at attempt 33.
**Fix**: Fresh LifecycleRegistry on camera restart. Proactive stale connection pruning via `sendUrgentData`. Camera health check with auto-restart. Backoff shift capped at 5.

## UI & Settings Conventions (MANDATORY)

**Every user-facing setting MUST be exposed in a single, advertised, discoverable in-app Settings
screen with an explicit control (on/off toggle, or a value picker).** This is a hard requirement
for all new features and applies retroactively to existing ones.

- **One advertised home for settings.** A clearly-labelled "Settings" entry the user can find — not
  buried in an ad-hoc "Tools" list, not API/`adb`-only, not implicit. If a feature has a tunable
  behaviour, its control lives in Settings.
- **Granular on/off control over each sub-behaviour.** When a feature bundles several effects, expose
  each one as its own toggle — never a single all-or-nothing switch.
  - *Example — Deaf/HoH alert mode (#303):* must offer independent toggles for **screen flash**,
    **vibration**, and **camera torch** (e.g. "vibrate + flash only", or "flash only"), not one
    combined "Deaf/HoH" checkbox. Same spirit for any multi-effect alert/feature.
- **Discoverable + labelled.** Each control needs a clear label (and ideally a one-line description),
  localized in all `values-*/strings.xml`.
- **Persist + honour at runtime.** Read the setting where the behaviour happens (the pure policy
  classes in `alarm/`, `events/`, etc. already model this) and persist it (SharedPreferences).
- **Definition of done for any new tunable feature:** the toggle(s) are in the Settings screen AND
  wired to actually change behaviour AND verified on a device.

Existing alert-style toggles (vibration-only #292, deaf/HoH #303) currently sit as checkboxes in the
StartActivity "Tools" block — they need migrating into the advertised Settings screen with the
granular per-effect controls described above.

## Common Development Tasks

### Adding a new API endpoint
Add to `WebControlServer.serve()` switch. Use `NanoHTTPD.newFixedLengthResponse()`.

### Adding a new WebSocket channel
1. Create inner class extending `WebSocket` in `WebControlServer`
2. Add to `openWebSocket()` URI switch
3. Track clients in `CopyOnWriteArrayList`

### Modifying audio codec
Update `AudioCodecDefines.kt` for constants. Encoder and decoder are both `audio/G711UCodec.kt`, used via `AudioCodecDefines.CODEC`. Both sides (child + parent) must match.

### Adding new log categories
Add to `LogManager.LogCategory` enum. Use `LogManager.i(LogCategory.NEW_CAT, "message")`.

### Testing on devices
```bash
# See both devices
adb devices

# Open child monitor app
adb -s <CHILD_SERIAL> shell am start -n de.rochefort.childmonitor/.StartActivity

# Open parent monitor app
adb -s <PARENT_SERIAL> shell am start -n de.rochefort.childmonitor/.StartActivity

# Watch live logcat for app
adb -s <SERIAL> logcat -s MonitorService:* ListenService:* LogManager:* CameraStreamer:* WebControlServer:*
```

### Permissions
Runtime permissions required: `RECORD_AUDIO`, `CAMERA`
Foreground service types: `microphone|camera` (child), `mediaPlayback` (parent)

## File Structure
```
app/src/main/
  AndroidManifest.xml
  kotlin/de/rochefort/childmonitor/
    MonitorService.kt          # Child service
    ListenService.kt           # Parent service
    ListenActivity.kt          # Parent UI
    MonitorActivity.kt         # Child UI
    StartActivity.kt           # Role selection
    DiscoverActivity.kt        # Device discovery
    WebControlServer.kt        # HTTP + WebSocket server
    CameraStreamer.kt           # CameraX wrapper
    LogManager.kt               # Diagnostic logging
    NetworkStateMonitor.kt      # Network diagnostics
    BatteryStateMonitor.kt      # Battery diagnostics
    BatteryOptimizationHelper.kt
    NsdDiscoveryHelper.kt       # mDNS helper
    AudioCodecDefines.kt        # Codec constants
    audio/G711UCodec.kt         # mu-law codec (encode + decode)
    VolumeView.kt               # Audio level visualization
    VolumeHistory.kt            # Audio level buffer
    ZoomableImageView.kt        # Pinch-to-zoom for snapshots
    FullscreenCameraActivity.kt # Landscape camera view
    LogViewerActivity.kt        # Diagnostic log viewer
  res/
    layout/                     # 10 activity layouts
    values/                     # Strings (EN, DE, JA, NL, PT-BR)
    drawable/, mipmap/          # Icons
    raw/                        # Alert sound
    xml/                        # Network security config, FileProvider
```
