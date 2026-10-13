# Local TV and tablet display

The parent live-view screen can send the child's camera to a display on the same LAN. The output is
intentionally video-only: monitoring audio continues through the parent phone, so putting the image
on a television does not make the child harder to hear.

## Supported paths

- **DLNA / UPnP MediaRenderer:** the app discovers televisions and renderers with SSDP and sends a
  standard AVTransport request. Try the live MJPEG stream first; for renderers that do not implement
  MJPEG, use the compatible JPEG slideshow.
- **TV or tablet browser:** share the private viewer link to a browser on the same Wi-Fi. The page is
  a full-screen, silent MJPEG view with no control, status, talkback, or audio endpoint access.

The media never traverses an application cloud service. Direct in-app output is provided for DLNA
and browser-capable displays. Chromecast users can still mirror the parent screen with Android's
system Cast control.

## Security model

The full parent bearer token is never copied into a display URL. The parent requests a separate
random capability over its pinned TLS control connection. A capability:

- can read only `/cast`, `/cast/snapshot`, and `/cast/stream.mjpeg`;
- cannot access audio, talkback, status, replay, pairing, controls, or metrics;
- binds atomically to the first renderer IP that uses it;
- expires after 12 hours, never persists, and is replaced when the same parent starts again;
- is revoked when the user stops local display or revokes that parent pairing; and
- is re-checked throughout an open MJPEG response, so revocation terminates an existing stream.

UPnP descriptions are untrusted LAN input. Discovery accepts only bounded HTTP descriptions from the
actual private responder address, rejects redirects and XML external entities, and permits the
AVTransport control URL to change ports but not hosts. SOAP and DIDL values are XML-escaped.

## Use

1. Connect to the child from the parent app and ensure the camera is on.
2. Tap **Show on a local TV or tablet**.
3. Choose **Find DLNA displays**, select a television, then choose live stream or slideshow; or use
   **Share private browser link** for a browser-capable local display.
4. Tap **Stop local display and revoke access** when finished.

The child and display must be mutually reachable on the same LAN. Guest Wi-Fi client isolation can
allow internet access while blocking this local traffic and discovery.
