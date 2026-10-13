# Security Model

Issue coverage: CM-SEC-001..008, CM-QA-003.

## Overview

Child Monitor is a local-first system. The only security boundary that matters
covers both LAN peers and other apps on the child phone. Device credentials stay
in app-private storage; loopback administration also requires proof from the child app.

## Trust primitives

| Artifact | Purpose | Storage |
|----------|---------|---------|
| `trusted_devices_v1` in PairingPreferences | Allowlist of paired parents | `getSharedPreferences` (AES-encrypted on API 23+ via Keystore if available) |
| Parent bearer/refresh credentials | Authenticate a parent to one child | AES-GCM envelope per value; random AES key wrapped by a non-exportable AndroidKeyStore RSA key |
| `childmonitor-tls-v3` | Stable self-signed identity for pinned HTTPS/WSS/audio TLS | Non-exportable AndroidKeyStore key; TLS explicitly selects this alias |
| `SessionAuthority` token store | Short-lived bearer tokens | `filesDir` |
| `media_ring.key` | AES-GCM key for the rolling event clip buffer | `filesDir` |
| `trusted_discovery_ecp.key` | EC private key for signing NSD TXT metadata | `filesDir` |

## Endpoints + authentication

| Endpoint | Public? | Notes |
|----------|---------|-------|
| `/` | yes | HTML console, embedded player |
| `/api/pairing/challenge` | child app only | Loopback plus a random process-local admin proof; PIN is displayed on the child |
| `/api/pairing/complete` | yes | Validated against challenge |
| `/api/session/issue` | yes | Issues token after successful `complete` |
| `/api/fingerprint` | yes | TLS SHA-256 for TOFU pinning |
| `/healthz` | yes | Liveness only; no state leak |
| `/metrics` | **bearer** | Device diagnostics |
| `/snapshot` | **bearer** | JPEG of last frame |
| `/api/status` | **bearer** | Structured device status |
| `/api/camera/*` | **bearer** | Toggle/enable/disable |
| `/api/pairing/devices` | **bearer** | List paired devices |
| `/api/pairing/revoke` | **bearer** | Remove paired device |
| `/ws/audio`, `/ws/talkback` | **bearer** | Live audio streams |

The child pairing screen uses a random, memory-only admin header over loopback.
A loopback address alone grants no access to status, paired devices, or revocation.
The header is never stored or included in parent pairing credentials.

## Threat model (high-level)

1. **Unpaired attacker on LAN**: cannot fetch snapshots or subscribe to
   audio without a valid bearer token (issued only after PIN pairing).
2. **Paired but compromised parent**: user can revoke via the paired device
   list; all of that device's tokens are invalidated immediately.
3. **Tampered child binary**: `IntegrityChecker` surfaces debug-mode, signing
   mismatches, root indicators, and emulator fingerprints. Observations
   append to the audit log and are shown in the security status UI.
4. **VPN failure**: `VpnInterfaceSelector` watches callbacks; when the VPN
   drops, the service stops streaming to prevent leaking the audio path to
   other interfaces.
5. **Parent storage compromise**: bearer and durable refresh credentials are never written as
   plaintext. Legacy values migrate atomically on first read. Ciphertext corruption or Keystore
   invalidation fails closed with a visible re-pair action; backups exclude plaintext and ciphertext
   credential keys while retaining non-secret TLS pins for recovery.

## Data retention

- Audit log rolls at 1 MB (one `.1` backup).
- Diagnostic logs rotate daily; `RetentionEngine` trims > 14 days.
- Event rows prune after 30 days.
- Rolling media buffer keeps 256 encrypted clips by default, reduced under
  filesystem pressure.

## Operator to-dos

- Rotate the media-buffer key by deleting `media_ring.key` (app regenerates
  on next launch; prior clips become unreadable).
- Quarterly security review: check `audit_log.jsonl` for unexpected
  `integrity_warn` / `tamper_suspect` entries, and review paired devices.
