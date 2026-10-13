# Privacy & Permissions Review

Issue coverage: CM-QA-003.

## Permissions requested

| Permission | Why | Runtime vs install | Data flow |
|------------|-----|--------------------|-----------|
| RECORD_AUDIO | Capture child audio | runtime | Device-local streaming |
| CAMERA | Snapshot/video | runtime | Device-local streaming |
| INTERNET | Local socket + HTTP server | install | No Internet egress |
| ACCESS_NETWORK_STATE | Detect VPN / connectivity | install | Metadata only |
| ACCESS_WIFI_STATE | Advertise via NSD | install | Metadata only |
| CHANGE_WIFI_MULTICAST_STATE | NSD | install | Broadcast discovery only |
| WAKE_LOCK | Keep FG service alive | install | Local |
| FOREGROUND_SERVICE_{MICROPHONE, CAMERA, MEDIA_PLAYBACK} | A14+ FG service types | install | Local |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Prompt user to whitelist | runtime | Local |
| RECEIVE_BOOT_COMPLETED | Relaunch service on reboot | install | Local |
| SYSTEM_ALERT_WINDOW | Show overlay for critical alerts (disabled by default) | runtime | Local |
| POST_NOTIFICATIONS | A13+ explicit opt-in | runtime | Local |

## Data we collect

- Diagnostic logs (`diagnostic_logs/*.txt`) — stay on device.
- Audit log (`audit_log.jsonl`) — stays on device.
- Event store (`events.db`) — stays on device.
- Encrypted rolling media buffer — stays on device.

Nothing leaves the device unless the user manually shares a diagnostic bundle.

## Third parties

- `androidx.*` — Apache-2.0, Google
- `com.google.*` (CameraX) — Apache-2.0, Google
- `org.nanohttpd.*` — BSD-3-Clause (Paul Hawke)
- `org.jetbrains.kotlin*` + `kotlinx-coroutines` — Apache-2.0, JetBrains
- `junit` + `kotlin-test` — EPL / Apache-2.0

None of these call out to remote servers at runtime.

## Local storage footprint

| Path | Size bound | Retention |
|------|-----------|-----------|
| `diagnostic_logs/` | 1 MB/day, rolled | 14 days (CM-STORE-002) |
| `audit_log.jsonl{,.1}` | 1 MB each | indefinite (two files) |
| `events.db` | grows with events | 30 days (CM-STORE-002) |
| `media_ring/` | 256 chunks | ring buffer |
| `session_checkpoints/` | < 1 KB per session | refreshed on next boot |
| `heartbeats/` | < 1 KB per service | overwritten every tick |

## User controls

- Clear app storage: standard Android setting.
- Revoke a paired device: parent device list → Revoke.
- Export bundle: Diagnostics → Export Bundle.
- Delete audit log: Diagnostics → Reset Security Logs (CM-SEC-005).
- Rotate media buffer key: Diagnostics → Reset Media Buffer.
