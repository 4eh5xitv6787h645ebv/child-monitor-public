# Privacy policy for Child Monitor

Updated 13 October 2026.

Child Monitor connects a child phone to one or more parent phones over a private network or VPN.
There is no required account or developer-operated audio/video relay.

## Monitoring and local storage

Microphone audio, camera snapshots, status, and alerts are sent directly to connected parent devices.
Pairing credentials, settings, diagnostic logs, optional recordings, and care-tracker entries are stored
in app-private storage on the relevant phone. Tracker entries may include information you enter about
a child, such as their name, dates, feeds, sleep, and notes.

Secure QR pairing uses pinned TLS. Legacy connections and the local HTTP console can use plaintext,
so use a trusted private network or VPN. Pairing remains until revoked or the relevant app data or
credential keys are removed.

## Optional network requests

Automatic update checking is enabled by default and checks GitHub release metadata at most once a
day when the app runs. GitHub receives normal connection metadata, including the requesting IP
address. The request does not upload monitoring media, tracker entries, or pairing credentials.
You can disable automatic update checking in Settings. Updates are notifications linking to a
release page; the app does not automatically download or install an APK.

Optional MQTT/Home Assistant integration sends configured status and event data to the broker you
choose. This integration is disabled unless you configure and enable it. The broker's privacy and
security depend on your chosen service and network.

## Exports and sharing

Backups, exports, and Android share actions can send selected data to a destination you choose.
Diagnostic bundles can contain device and network information. Review them before sharing and
remove private details. The app does not automatically submit diagnostic bundles to the developer.

## Permissions and controls

Microphone and camera permissions enable monitoring features; notification and other optional
permissions enable the corresponding alerts, background operation, or device controls. The app
does not request location permission to track your location.

You can revoke a parent on the child phone, change settings, delete supported local logs/recordings,
and remove app data using Android Settings. Clearing app data or uninstalling removes local pairing
and requires pairing again. Copies you previously exported must be removed from their destination
separately. Android's own storage and backup settings also apply.

## Contact

Questions about this version can be raised at
[the public project](https://github.com/4eh5xitv6787h645ebv/child-monitor-public/issues).
Public issues are visible to others; exclude pairing secrets, QR codes, personal records, and private
diagnostic captures. Changes to this policy are recorded in this file's Git history.
