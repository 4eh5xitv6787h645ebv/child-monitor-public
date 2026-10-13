# Child and multiple-parent release smoke test

Run this gate against the exact signed release APK before publishing. Use at least three physical
phones on the same Wi-Fi network: one child and at least two parents, all running Android 14 or newer. Record model, Android
version, APK SHA-256, release-certificate SHA-256, start/end time, tester, and pass/fail evidence.

## Preparation

1. Install the candidate APK on all phones and confirm the version shown is the release version.
2. Grant camera, microphone, notification, overlay, exact-alarm, battery-optimization, and device
   admin permissions only when the relevant screen requests them.
3. Clear logcat, start an app diagnostic capture on all phones, and keep a USB cable available.
4. On the child phone enable Child Mode auto-start; on each parent enable Parent auto-reconnect.

## Required matrix

| Area | Procedure | Pass evidence |
| --- | --- | --- |
| Pair once | Scan the child QR once, connect, stop both apps, and reconnect from the saved child entry without rescanning. | Same child identity and pinned certificate are reused; no PIN or QR prompt. |
| Multiple parents | Scan the same child QR on each parent and keep both listening. Reconnect one parent, renew its access, and use talkback while the other listens. | Each parent retains its permanent credential; neither phone evicts the other or asks to pair again; both audio streams recover after talkback. |
| Independent revocation | Revoke one parent on the child while both listen. | Only that parent's native/WebSocket audio and talkback close; the other keeps listening and can renew. |
| IP change | Change the child's address by roaming to another access point or renewing DHCP, then open the saved child on the parent. | Discovery updates the endpoint by device ID and reconnects without rescanning or disabling TLS pinning. |
| Child audio | Speak near the child for two minutes at quiet and loud levels. | Parent audio is continuous, intelligible, and does not clip or stall. |
| Parent listen while soothing | While listening, play Brahms and each noise/tune on the child at low and high volume. | Soother is audible at the child; parent monitoring remains intelligible and does not route or play the lullaby locally. |
| Talkback | Hold parent talkback and speak, including while the soother plays. | Child hears the parent; parent playback ducks only as designed and recovers immediately. No feedback loop. |
| Camera | Open snapshots/live view, rotate the parent, background/foreground it, and enter/leave PiP. | Frames resume without a black view, camera lock, crash, or unintended child-screen wake. |
| Alerts | Trigger noise, disconnect, battery, and configured motion/sound thresholds; test sound, vibrate-only, flash, and full-screen modes. | Exactly one timely alert appears per event with the configured effect and correct child name. |
| Night light | Set each preset/color and drag brightness from the parent. | Only the child screen changes; state survives parent rotation/reconnect. |
| Bedtime timer | Start night light plus Brahms, select each timer class (short test value and one real duration), then let it expire. | Sound and light stop, the child screen locks/turns off when device admin is enabled, and parent listening remains connected. |
| Child-mode escape | Lock/unlock, press Home/Recents/Back, rotate, and reconnect USB without using the parent wake control. | Child Mode remains contained according to setup policy and does not expose controls to the child. |
| Process kill | Force-stop/kill the parent process during a session, then the child process, and wait for watchdog recovery. | Parent reconnects to the saved child; child monitoring restarts without a QR scan. |
| Stable boot | Reboot the child once and verify no monitoring starts before Android is fully booted plus the one-minute stability window. Repeat a forced reboot during that minute. | The timer resets on the second reboot and Child Mode automatically returns only after a full stable minute; no restart loop. |
| Permission denial | Deny each runtime permission once, relaunch, then grant it. | The app explains the missing capability, does not crash, and recovers without clearing pairing. |
| Screen-off listen | Turn the parent screen off for ten minutes while audio/alerts remain active. | Audio and alerts continue; battery/foreground-service notification state is correct. |
| TalkBack — child | Enable TalkBack on the child. Navigate Start, Child Mode, QR pairing, monitor status, night light, and the on-device tracker using swipe/focus gestures only. Exercise a parent connection and disconnect while focus is elsewhere. | Reading order follows visual order, controls have concise unique names and roles, QR/monitor/parent-count transitions are spoken once, and there are no focus traps or decorative garden nodes. |
| TalkBack — parent | Enable TalkBack on the parent. Navigate QR scan, saved Kids, discovery/reconnect, Listen, camera/fullscreen/PiP, local-display setup, alert settings, and every tracker quick action without sight. Trigger discovery, reconnect success/failure, and pairing revoke. | Status changes are announced, all important images/sliders/actions are operable and named, fullscreen exposes an accessible exit/zoom action, and focus returns predictably after dialogs and child screens. |
| Largest text | Set Android **Font size** to its largest value on both phones. Repeat Start, Settings, pairing, Monitor, Listen, local display, and tracker entry/timeline flows in portrait and landscape where supported. | Text scales, wraps, and remains scrollable; no label, safety warning, value, or button text clips or overlaps; all actions remain at least 48 dp. |
| High contrast | Enable Child Monitor's High contrast setting on each phone and repeat the live view, alert settings, night timeline, trends, volume graph, and tracker dashboard/entries. | Normal text meets AA contrast, state colors remain distinguishable, charts and focus indicators remain legible, and switching the setting updates visible screens without a restart. |

## Automated process restart coverage

With debug and instrumentation APKs installed on an Android 14+ emulator, run:

```sh
scripts/run-process-restart-pairing.sh emulator-5586
```

Use the serial of the chosen emulator. The script saves two independent encrypted parent
credentials, force-stops the app, and verifies both renew and receive simultaneous pinned audio
in a fresh process without another PIN or QR scan. It records evidence under
`app/build/device-diagnostics/`. This supplements the physical-phone release matrix above.

## Failure capture

For any failure, do not rescan, clear storage, or restart before collecting evidence. Export both
in-app diagnostic bundles, `adb logcat -b all -d`, `adb bugreport`, screenshots/video, the exact
step and wall-clock time, network addresses before/after, and whether either process or phone
restarted. Link the resulting issue to Project #6 and mark the release gate failed.
