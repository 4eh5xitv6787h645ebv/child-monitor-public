# Child Monitor Dev
An open source, local-first baby monitor for Android 14 and newer.

[Download the latest signed release](https://github.com/4eh5xitv6787h645ebv/child-monitor-public/releases/latest).

Child Monitor Dev connects one child phone to one or more parent phones:
1. A **Child Device** stays with the baby and streams audio (plus low-bandwidth camera snapshots).
2. Each **Parent Device** listens in real time, can view snapshots/fullscreen feed, and can push-to-talk.

The app is designed for long-running sessions and local-network usage (including VPN to the same private network).

## What The App Is For
- 24/7 local baby monitoring without cloud relay dependency
- Multi-parent listening from Android devices
- Camera snapshot visibility with adaptive refresh for unstable links
- Local web console on child device (`http://<child-ip>:11000`) for status + snapshot + browser audio
- Diagnostics tooling for connection, network, audio, and power behavior
- Optional local [MQTT / Home Assistant integration](docs/mqtt-home-assistant.md)
- Setup and calibration tools for reliability on real phones

## Multiple Parent Phones

Scan the child device's secure QR on each parent phone. Each phone receives its own permanent
pairing credential and can listen at the same time (up to eight audio connections). Short-lived
access tokens renew automatically after expiry, network changes, or child-service restarts.
Pairing lasts until explicitly revoked or app data/credential keys are removed. The child preserves
its QR certificate identity across restarts; certificate ageing does not replace parent TLS pins.

Update the child and all parent phones for independent parent identities. Existing pairings migrate
when they next make a successful authenticated connection; no new scan is needed while a valid
bearer is available. A credential already overwritten by an older child build cannot be recovered
if its bearer has also expired; scan once after updating to establish the independent pairing.
Revoking one independently paired parent preserves the others' access.

## Safety And Security Model
This app is built for **local/private network use**. Security posture is:

1. **No cloud relay required**
   - Child and parent connect directly over local network/VPN.
   - There is no mandatory external backend/account login flow in the app.

2. **Local data storage**
   - Logs, pairing state, and calibration values are stored in app-private Android storage.

3. **Pairing controls**
   - Local pairing challenge/PIN endpoints are available from the child-side web service.
   - Trusted parent device IDs are managed locally.

4. **Operational hardening**
   - Battery optimization checks/warnings
   - Network and power-state diagnostics
   - Reconnect and service health handling

Important security note:
- Audio/video transport in this app is designed for trusted local networks and VPNs. It is **not a cloud end-to-end encrypted service**.
- Use only on private Wi-Fi/VPN that you control.
- Do not expose child ports (`10000`, `11000`) to the open internet.

## Safe Usage Checklist
1. Keep both devices on your private Wi-Fi or private VPN.
2. Set battery mode to **Unrestricted** for reliability.
3. Keep app permissions limited to what the app needs (mic/camera/network/notifications).
4. Review paired devices and revoke any unknown entries.
5. Use the diagnostics/timeline screens if connections drop or drift.

## Running On Different Networks (VPN)
If both phones are not on the same Wi-Fi, connect both to the same VPN network.
1. Auto discovery may not work across some VPN setups.
2. Enter child IP manually on parent when needed.
3. The child service usually listens on port `10000` (unless occupied).

## License Information
Child Monitor is licensed under GPLv3.
The μ-law codec code is Apache 2.0 (from AOSP).

## Thanks
- Audio file originals from [freesound](https://freesound.org).
- Original project by [brarcher](https://github.com/brarcher/protect-baby-monitor).
