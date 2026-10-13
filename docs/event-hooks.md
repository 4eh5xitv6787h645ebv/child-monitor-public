# Local event hooks

Event hooks are opt-in and disabled by default. Configure them from the parent app under
**Child controls → Alerts & webhooks**. The child can send an Android broadcast and optionally an
HTTP(S) POST to a user-chosen webhook. **LAN only** blocks webhook delivery unless every resolved
address is private/local.

Supported event names:

- `parent_connected` and `parent_disconnected`
- `sound` for an alert-worthy classified sound
- `cry` in addition to `sound` when that sound is classified as a cry
- `motion` after sustained child-camera movement
- `test` from the **Send test event** button

Cry, cough, and motion events honor their individual alert toggles and quiet-hours escalation
floor before hooks are emitted. Repeated sound regions are cooldown-limited; motion detection also
requires sustained changes and has a cooldown.

Webhook payloads contain `event` and `ts` plus event details such as confidence and duration. The
implicit broadcast action is `de.rochefort.childmonitor.EVENT`; it receives only coarse details and
does not include the durable database ID or sensitive connection data.
