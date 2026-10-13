# ADR-0001: Local-only media stack

Status: Accepted
Date: 2026-04-18
Issue: CM-DOC-001

## Context

Child Monitor streams audio, camera snapshots, and talkback between a child
device and one or more parent devices. The app targets local-network and VPN
topologies. Multiple OSS projects explored during 2026-02 research — MediaMTX,
SRS, RecordRTC, go2rtc, and tinode/chat — offer high-capacity streaming at the
cost of operating a server alongside the phones.

## Decision

We keep a local-only media stack:

1. **No cloud relay.** All audio/video flows directly over the local network or
   a user-supplied VPN. No Anthropic/Google/AWS endpoints are used at runtime.
2. **Plain TCP + NanoHTTPD + WebSockets.** The existing mu-law TCP path
   remains the canonical transport. New protocol adapters (Opus, WebRTC, SRT)
   plug in through `de.rochefort.childmonitor.protocol.MediaAdapter` without
   changing callers.
3. **Device pairing on-device.** PINs and QR codes are generated on the child;
   the trust root is a user-controlled key pair. No account or identity server
   is required.

## Consequences

- Pros: Runs offline. No back-end SLOs. Privacy is a property of the network.
- Cons: No built-in relay; users outside the home need to stand up a VPN.
- Follow-up:
  - CM-NET-012 QoS hints (local only)
  - CM-PROTO-002 API versioning (clients in different build trains)
  - CM-SEC-003 TLS on every local link
