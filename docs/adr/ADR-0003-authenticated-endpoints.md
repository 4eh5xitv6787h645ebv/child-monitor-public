# ADR-0003: Authenticated endpoints + bearer session tokens

Status: Accepted
Date: 2026-04-18
Issue: CM-SEC-001, CM-SEC-002, CM-SEC-004

## Context

The embedded `WebControlServer` (NanoHTTPD + NanoWSD) initially exposed
`/snapshot`, `/api/status`, `/ws/audio`, and `/ws/talkback` without any
authentication. Any device on the same network could subscribe to the audio
stream or push talkback audio, even if the user had paired the monitor with a
specific phone.

## Decision

1. Retain the existing `PairingManager` (PIN + QR).
2. Introduce `SessionAuthority` which issues short-lived bearer tokens bound
   to a `deviceId` after a successful pairing handshake.
3. `WebControlServer` gates every non-public endpoint behind the standard
   `Authorization: Bearer <token>` header. Bearer credentials are never accepted in a query
   parameter: URLs are routinely retained by browser history, proxies, access logs, and audit
   events.
4. The exact anonymous bootstrap surface is `/`, `/api/pairing/challenge`,
   `/api/pairing/complete`, `/api/session/issue`, `/api/session/refresh`,
   `/api/fingerprint`, and `/healthz`. Pairing and session bootstrap endpoints additionally enforce
   their own PIN/refresh credential, method, rate-limit, and TLS-or-loopback requirements. A path
   prefix is never treated as an allowlist entry.
5. WebSocket upgrades run the same header-token check; unauthenticated sockets are
   closed immediately with a policy-violation code.
6. Issuance is rate-limited per deviceId via `AuthRateLimiter` (5/min, 5-min
   lockout) to prevent PIN brute-forcing.
7. All auth events append to an on-disk JSONL audit log (`AuditLog`).

## Consequences

- Each parent installation has its own identity and short-lived bearer. Secure QR bootstrap
  exchanges the shared QR bearer for an independent bearer and permanent refresh credential.
  The QR remains available for additional parents; another scan never replaces their credentials.
- Refresh credentials are indexed by their secret hash with an explicit owner identity, including
  legacy credentials that shared the child's ID. Rotation, renewal, and revocation of independent
  parents do not invalidate other parents. The parent atomically persists its bearer, encrypted
  refresh secret, and refresh owner ID while continuing to index TLS pins by the child ID.
- Anyone on the LAN sees only the bootstrap HTML/health surface. Audio, snapshots, controls,
  paired-device administration, and operational metrics require an authenticated parent session.
- `/metrics` is authenticated because it includes monitor/session state and stable token prefixes;
  `/healthz` alone stays anonymous and returns only a coarse status and timestamp.
- Standard consumers that cannot set an `Authorization` header must use a separately scoped,
  short-lived capability designed for that individual media surface. The full parent bearer is
  never copied into a URL.
- When TLS becomes mandatory (CM-SEC-003 + CM-SEC-008 TOFU) the same tokens
  extend unchanged.
