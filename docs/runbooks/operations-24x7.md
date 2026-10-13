# 24/7 Operations Runbook

Issue: CM-DOC-002

This runbook covers the operational needs of running Child Monitor overnight
on a pair of Android phones. It assumes both devices are on mains power and
reachable via LAN or VPN.

## Daily Pre-Flight Checklist

| Check | How | Expected |
|-------|-----|----------|
| Child app foreground | Phone screen or notification shade | "Child Monitor" persistent notification present |
| Battery optimization | Settings → Apps → Child Monitor → Battery | "Unrestricted" |
| Foreground service | `adb shell dumpsys activity services de.rochefort.childmonitor` | `MonitorService` running |
| VPN (if used) | VPN client status | Connected |
| Parent can reach child | Parent app "Listening…" after < 15 s |

## Common Incidents

### 1. Parent shows "Connecting…" indefinitely

1. Check VPN on both phones.
2. Confirm the child's IP on the diagnostics page; parent must hit the same IP.
3. Pull one of the child's heartbeat files: `adb shell "run-as de.rochefort.childmonitor cat files/heartbeats/*.json"`
   - If `tsMonoMs` is stale > 2 min → service is hung; reboot child app.

### 2. No audio but snapshots work

1. Check the talkback indicator on the child UI — if stuck "active", it may
   be holding the mic. Revoke talkback via `/api/pairing/revoke`.
2. Look at `MonitorService` logs for `AudioRecord ERROR_DEAD_OBJECT`.

### 3. Camera marked "Stale"

See CM-VID-007. If the parent UI shows a **frozen frame** warning:
1. Pull the last 60 s packet ring via `/metrics` (includes ring dump as JSON).
2. If the child's `stale-frame` counter is climbing, restart the camera via
   `/api/camera/off` then `/api/camera/on`.

### 4. Service missing after reboot

The `BootReceiver` re-arms the service. If missing:
1. `adb shell am start-foreground-service -n de.rochefort.childmonitor/.MonitorService`
2. Investigate battery-optimization whitelisting (Doze can veto autostart).

## Escalation

All events that flip the audit log (`audit_log.jsonl`) with
`event=integrity_warn` or `event=tamper_suspect` should cause an operator to:
1. Note the event timestamp and device.
2. Pull the diagnostics bundle: long-press Diagnostics → Export Bundle.
3. File a bug with the bundle attached to the private security channel.
