# Runtime feature inventory

Project #6 issue #266 found classes that had unit tests but no production caller. That creates a
dangerous ambiguity: a green test can look like proof that a safety or reliability feature actually
runs on a phone. The audit below records the deliberate outcome for every module named by that
issue. Only runtime-wired behavior is kept in the app.

## Live paths

| Capability | Runtime path |
| --- | --- |
| Sound/cry/cough detection | `MonitorService` feeds pre-gain PCM to `SoundEventDetector`, persists through `EventStore`, and exposes recent events through authenticated status. |
| Motion detection | `CameraStreamer` samples luminance continuously through `MotionEventDetector`; `MonitorService` persists and dispatches sustained events. |
| Parent alerts | `ListenService` polls the pinned-TLS status link, applies freshness/de-duplication/mute policy, and calls `EventNotifier`. |
| Alert retention | `MonitorService` supplies the live `EventStore` to `RetentionEngine`. |
| Adaptive snapshots | `MonitorService.recomputeSnapshotCadence()` calls `AdaptiveSnapshotController` and applies the result to `CameraStreamer`. |
| Video liveness | `ListenActivity` uses `VideoLivenessPolicy` plus the active fetch-failure streak; the child separately restarts stalled camera capture. |
| Multi-child switching | `KidsActivity`, `PairedChildrenStore`, and `KidConnect` use stable child device IDs and per-child credentials. |
| Health alerts | Dedicated battery, connection, capture-health, and event notifiers own their respective live signals. |
| Night summaries | `NightMetricsStore`, Night Timeline, multi-night trends, and the durable event timeline own recorded summaries. |

## Retired scaffolding

The following caller-free classes and their isolated tests were removed after the audit:

- `EventFusion`: requiring correlated sensors is not the alert contract and could suppress a valid
  cry when the child is still. Sound and motion therefore remain independently actionable.
- `ReconnectAnomalyDetector`: reconnect/discovery state, backoff, credential renewal, and visible
  failure reasons already live in `ListenService`/`ListenActivity`; the detector emitted nowhere.
- `StaleFrameDetector`: superseded by the cadence-aware `VideoLivenessPolicy`, snapshot failure
  streak, and child camera watchdog.
- `MotionRoiSelector`: there is no shipped auto-crop control, and silently cropping a monitor view
  can hide relevant parts of the room. Whole-frame motion remains live.
- `DailySummary`: superseded by the persisted night timeline, trends, and event timeline.
- `HealthAlerts`: superseded by the live signal-specific notifiers; evaluating JSON without a caller
  delivered no alert.
- `MultiChildProfiles`: superseded by the stable-device-ID Kids roster and fast switch.

`MotionDetector`, `FalsePositiveLoop`, `LullabyController`, `IntercomMode`, and `MqttBridge` from the
original issue had already been removed in the earlier dead-code audit. MQTT/Home Assistant is a
separate explicit feature request (#305), not a hidden tested implementation.
