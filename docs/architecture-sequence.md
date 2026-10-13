# Architecture & Sequence Diagrams

Issue coverage: CM-DOC-001.

## Component overview

```
+---------------------+            +-------------------------+
|   Child Device      |            |   Parent Device          |
|                     |            |                         |
|  MonitorService     |            |  ListenService          |
|   ├── AudioRecord   |            |   ├── AudioTrack        |
|   ├── CameraStreamer|  TCP 10000 |   ├── Snapshot fetcher  |
|   ├── WebControl    |<---------->|   ├── Talkback WS       |
|   │    (port 11000) |            |   └── Reconnect loop    |
|   └── PairingMgr    |            |                         |
+---------------------+            +-------------------------+

  NSD (_childmonitor._tcp.)        NSD client / manual IP
```

## Pairing sequence

```
Parent               Child
  |                     |
  |--- GET /api/pairing/challenge ---->|
  |<----- 200 { pin, qrPayload } ------|
  |                     |
  |-- GET /api/session/issue?code=PIN&deviceId=... -->|
  |<----- 200 { token, expiresAtMs } -----|
  |                     |
  |---- WS /ws/audio (Bearer <token>) ---->|
  |<========= mu-law stream =============|
```

## Audio path (child → parent)

```
AudioRecord (MIC 8 kHz PCM16)
     │
     ▼
LoudnessNormalizer (AGC + limiter) — CM-AUD-010
     │
     ▼
NoiseGate (optional) — CM-AUD-011
     │
     ▼
TalkbackDucking (when parent is talking) — CM-AUD-015
     │
     ▼
G711UCodec.encode (μ-law)
     │
     ├────► TCP 10000 → ParentConnection
     └────► WebSocket /ws/audio (browser player)
```

## Parent jitter buffer

```
TCP socket → JitterBuffer.enqueue(packet)
                        │
                        ▼
             PacketLossConcealer
                        │
                        ▼
               AudioTrack.write(pcm)
```

## Video snapshot rate

```
CameraX imageCapture → CameraStreamer → SnapshotPyramid
                                 │
                                 ▼
         Parent fetches /snapshot at interval driven by
         AdaptiveSnapshotController(motion, thermal, battery)
```
