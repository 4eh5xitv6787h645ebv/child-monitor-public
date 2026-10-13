# Troubleshooting Decision Tree

Issue: CM-DOC-003

Use this tree on the **parent device** when the monitor stream is not working.
Every branch ends with either a resolution or a structured bug report.

```
Is the child app showing "Streaming..." on its screen?
├── No → see "CHILD_NOT_STREAMING" below
└── Yes
    ├── Is there a VPN in use?
    │   ├── Yes → is the VPN tunnel up on BOTH phones?
    │   │   ├── No → start VPN; this is almost always the cause.
    │   │   └── Yes → continue to AUDIO/VIDEO/PERM branches.
    │   └── No → both phones on the same Wi-Fi SSID?
    │       ├── No → reconnect to same SSID.
    │       └── Yes → continue.
    ├── Permissions (child side)
    │   ├── Mic permission granted?   No → grant, restart app.
    │   ├── Camera permission granted? No → grant, restart app.
    │   └── Notifications allowed?    No → grant (needed for FG service).
    ├── Battery
    │   ├── Child app battery set to "Unrestricted"? No → set, then reboot
    │   │   child phone. Doze otherwise pauses network after the screen
    │   │   sleeps.
    │   └── Screen-off timeout < 60 s? → fine (unless VPN drops on sleep).
    └── Audio route (parent side)
        ├── Bluetooth connected? If yes, disconnect to test speaker.
        ├── Volume at max? → raise.
        └── Other media app playing? → pause; Child Monitor uses
            STREAM_MUSIC with ducking.
```

## CHILD_NOT_STREAMING

```
Does the child notification persist?
├── No → service was killed. Likely Doze/OEM battery-saver.
│        Fix: "Unrestricted" battery + manufacturer autostart whitelist.
└── Yes
    ├── "Failed to initialize AudioRecord" in logs?
    │   → another app owns the mic. Close call/voice apps, reopen.
    ├── "Failed to open server socket" → another app is on port 10000.
    │   Reboot phone or adjust conflicting app.
    └── Camera health check repeated restart?
        → quarantined; audio-only fallback is active (see CM-VID-013).
```

## Covered on the diagnostics screen

The app also exposes this tree through the **Diagnostics → Troubleshoot** action,
which runs live checks against the current state (VPN up, permissions granted,
battery optimization relaxed, audio route sane, camera producing frames) and
prints the first failing check.
