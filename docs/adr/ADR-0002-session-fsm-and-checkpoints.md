# ADR-0002: Crash-safe session FSM with persisted checkpoints

Status: Accepted
Date: 2026-04-18
Issue: CM-REL-002, CM-REL-006

## Context

Parent and child sessions can die from a variety of causes (OS LMK, battery
saver, VPN handoff, Android Doze). The heartbeat tracking in `MonitorService`
helps but there is no explicit state model, so restart logic has to infer
what was happening before the crash.

## Decision

Introduce a deterministic finite-state machine over `SessionState`:

```
IDLE → STARTING → CONNECTING → CONNECTED → ACTIVE → DRAINING → CLOSED
                                              ↘ FAILED ↙
```

`SessionStateMachine` writes each transition to `session_checkpoints/<name>.json`
along with the monotonic and wall clocks. On cold start, the service reads the
last checkpoint and decides whether to re-arm (`FAILED` → `STARTING`) or wait
for an explicit user action.

## Consequences

- Crash recovery is explicit; we no longer rely on log archaeology.
- Tests can assert transition legality without a device (see
  `SessionStateTest`).
- Checkpoints are small JSON blobs; a retention sweep removes stale names.
