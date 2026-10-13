package de.rochefort.childmonitor

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import de.rochefort.childmonitor.video.MjpegFraming
import de.rochefort.childmonitor.video.MjpegFrameStream
import de.rochefort.childmonitor.video.MjpegStreamLimiter
import de.rochefort.childmonitor.security.PairingTransportPolicy
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocketFactory

/**
 * Embedded HTTP + WebSocket server that exposes:
 *  - /                 : a lightweight monitoring console (HTML + JS)
 *  - /snapshot         : latest JPEG from the camera
 *  - /api/replay/audio : last N seconds of buffered audio (mu-law) for post-alert replay — #284
 *  - /api/status       : JSON with current state/metrics
 *  - /api/camera/toggle : Toggle camera on/off
 *  - /api/snapshot/cadence : Adjust snapshot cadence (mode / near-live glance) — #287
 *  - /api/soother      : Play/stop soothing noise on the child speaker — #311
 *  - /api/nightlight   : Toggle/adjust the child-screen night-light — #312
 *  - /api/hooks[/test] : Configure broadcast/webhook event hooks (+ test) — #308
 *  - /api/alerts/config: Per-trigger alert toggles + quiet hours — #291
 *  - /api/mqtt/config  : Configure the opt-in MQTT/Home Assistant bridge — #305/#315
 *  - /ws/audio         : mu-law audio frames streamed over WebSocket (binary)
 *  - /ws/talkback      : Receive audio from parent to play on child speaker
 */
class WebControlServer(
    port: Int,
    private val statusProvider: () -> JSONObject,
    private val snapshotProvider: () -> ByteArray?,
    private val cameraToggleHandler: (() -> Boolean)? = null,
    // #287: (modeString, glanceMs) -> result JSON. Either set the cadence mode or trigger a glance.
    private val snapshotCadenceHandler: ((String?, Long?) -> org.json.JSONObject)? = null,
    // #311/#328: (action, sound, volume, durationMs) -> result JSON. Control the child-speaker soother.
    private val sootherHandler: ((String?, String?, Float?, Long?) -> org.json.JSONObject)? = null,
    // #312/#490: (on, color, brightness, durationMs) -> result JSON. Control light + its timer.
    private val nightLightHandler: ((String?, String?, Float?, Long?) -> org.json.JSONObject)? = null,
    // #284: (seconds) -> last N seconds of buffered mu-law audio for post-alert replay.
    private val replayAudioProvider: ((Int) -> ByteArray)? = null,
    // #337: snapshot replay. List the buffered frame timestamps; fetch a frame's JPEG by timestamp.
    private val replaySnapshotTimestampsProvider: (() -> List<Long>)? = null,
    private val replaySnapshotFrameProvider: ((Long) -> ByteArray?)? = null,
    // #308: (enabled, webhookUrl) -> result JSON. Configure event hooks; + a test trigger.
    // #332: third arg lanOnly restricts webhook egress to private/LAN addresses.
    private val hooksHandler: ((String?, String?, String?) -> org.json.JSONObject)? = null,
    private val hooksTestHandler: (() -> org.json.JSONObject)? = null,
    // #291: per-trigger alert toggles + quiet hours (cry, cough, quiet, quietStart, quietEnd, floor).
    private val alertConfigHandler: ((String?, String?, String?, String?, String?, String?, String?) -> org.json.JSONObject)? = null,
    private val mqttConfigHandler: ((org.json.JSONObject) -> org.json.JSONObject)? = null,
    // #307: capability issuance is bearer-authenticated; the resulting grant is restricted to the
    // cast image surface and can therefore be placed in a renderer URL without exposing the full
    // parent account. Validator receives (grant, actual TCP peer address) for first-use IP binding.
    private val castSessionIssuer: ((String?) -> org.json.JSONObject?)? = null,
    private val castSessionRevoker: ((String?) -> Boolean)? = null,
    private val castTokenValidator: ((String?, String?) -> Boolean)? = null,
    private val castViewerTextProvider: (() -> Pair<String, String>)? = null,
    private val talkbackAudioHandler: ((Any, ByteArray) -> Unit)? = null,
    private val talkbackStoppedHandler: ((Any) -> Unit)? = null,
    private val pairingChallengeProvider: (() -> JSONObject)? = null,
    private val pairingCompleteHandler: ((String, String, String?) -> JSONObject)? = null,
    private val pairedDevicesProvider: (() -> JSONObject)? = null,
    private val pairingRevokeHandler: ((String) -> Boolean)? = null,
    private val tokenValidator: ((String?) -> Boolean)? = null,
    private val localAdminValidator: ((String?) -> Boolean)? = null,
    private val authenticatedDeviceIdProvider: ((String?) -> String?)? = null,
    private val audioListenersChangedHandler: (() -> Unit)? = null,
    private val tokenIssuer: ((String, String, String?) -> org.json.JSONObject?)? = null,
    // #298: rotate the caller's current bearer token (passed in) to a fresh one; null if invalid.
    private val tokenRotator: ((String?) -> org.json.JSONObject?)? = null,
    // #348: durable refresh credentials. Issuer mints one for the authenticated caller's device
    // (takes the caller's bearer); redeemer exchanges (deviceId, refreshToken) for a fresh bearer.
    private val refreshCredentialIssuer: ((String?, String?) -> org.json.JSONObject?)? = null,
    private val refreshRedeemer: ((String?, String?) -> org.json.JSONObject?)? = null,
    // #348: true only on the TLS listener instance. The refresh endpoints require TLS (or loopback)
    // so the long-lived credential never crosses cleartext on the LAN.
    private val isTlsListener: Boolean = false,
    private val authFailureListener: ((String, String) -> Unit)? = null,
    private val metricsProvider: (() -> org.json.JSONObject)? = null,
    private val fingerprintProvider: (() -> String?)? = null,
    private val httpsPortProvider: (() -> Int?)? = null,
    private val audioTlsPortProvider: (() -> Int?)? = null,
    sslSocketFactory: SSLServerSocketFactory? = null
) : NanoWSD(port) {

    init {
        // #129: when a TLS factory is supplied, NanoHTTPD wraps every accepted
        // socket in a TLS handshake. The plain-text port (default 11000) is
        // skipped entirely in this mode — the caller (MonitorService) runs a
        // second WebControlServer instance on the plaintext port when both
        // transports are required.
        if (sslSocketFactory != null) {
            makeSecure(sslSocketFactory, null)
        }
        // #207: bound concurrent connections so a LAN flood can't spawn unbounded
        // threads. Transparent below the cap (normal use is a handful of parents).
        setAsyncRunner(BoundedAsyncRunner(MAX_CONCURRENT_HTTP_CONNECTIONS))
    }

    private val audioClients = CopyOnWriteArrayList<AudioSocket>()
    private val talkbackClients = CopyOnWriteArrayList<TalkbackSocket>()
    private val connectionId = AtomicInteger(0)
    // #306: cap concurrent MJPEG streams so long-lived /stream.mjpeg connections can't starve the
    // bounded HTTP worker pool that also serves control + WebSocket audio.
    private val mjpegLimiter = MjpegStreamLimiter()

    private val currentClientSocket = ThreadLocal<java.net.Socket>()

    override fun createClientHandler(socket: java.net.Socket, inputStream: InputStream): ClientHandler =
        object : ClientHandler(inputStream, socket) {
            override fun run() {
                currentClientSocket.set(socket)
                try { super.run() } finally { currentClientSocket.remove() }
            }
        }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket {
        if (tokenValidator != null) {
            val token = bearerToken(handshake)
            if (!tokenValidator.invoke(token)) {
                authFailureListener?.invoke(handshake.uri, clientAddress(handshake) ?: "")
                return RejectingSocket(handshake)
            }
        }
        if (handshake.uri == "/ws/audio" || handshake.uri == "/ws/talkback") {
            // NanoHTTPD's timeout bounds the initial HTTP request. After authenticated upgrade,
            // a downstream-only listener need not send anything for its whole session. Its
            // bounded audio writer detects a stalled receiver; TCP keepalive detects lost peers.
            currentClientSocket.get()?.let { socket ->
                socket.soTimeout = 0
                socket.keepAlive = true
            }
        }
        // #204: cap inbound frame size before NanoWSD allocates the payload
        // buffer, so a single oversized frame can't OOM-kill the service.
        val limited = FrameLimitedSession(handshake, MAX_WS_FRAME_BYTES)
        return when (handshake.uri) {
            "/ws/audio" -> AudioSocket(limited)
            "/ws/talkback" -> TalkbackSocket(limited)
            else -> LoggingSocket(limited)
        }
    }

    /**
     * Wraps a handshake session so the WebSocket frame reader consumes a
     * [WebSocketFrameLimitInputStream]. Everything except the input stream
     * delegates to the real session (#204).
     */
    private class FrameLimitedSession(
        delegate: NanoHTTPD.IHTTPSession,
        maxFrameBytes: Long
    ) : NanoHTTPD.IHTTPSession by delegate {
        private val limitedStream = WebSocketFrameLimitInputStream(delegate.inputStream, maxFrameBytes)
        override fun getInputStream(): InputStream = limitedStream
    }

    /** Socket that closes immediately with a policy violation code. */
    inner class RejectingSocket(handshake: NanoHTTPD.IHTTPSession) : WebSocket(handshake) {
        private val path = handshake.uri
        override fun onOpen() {
            Log.w(TAG, "Rejecting unauthenticated websocket: $path")
            try {
                close(WebSocketFrame.CloseCode.PolicyViolation, "unauthorized", false)
            } catch (_: Exception) { }
        }
        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {}
        override fun onMessage(message: WebSocketFrame) {}
        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException) {}
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        // Public endpoints: the console HTML, pairing setup, and token issuance are
        // reachable without auth. Everything else requires a valid bearer token when
        // a token validator has been supplied.
        // #194-7 SECURITY: the previous allowlist used a startsWith for /api/pairing/
        // which exposed BOTH /api/pairing/devices (read paired children) and
        // /api/pairing/revoke (evict a paired parent) to any anonymous LAN peer.
        // An attacker could revoke the legitimate parent, then pair themselves in
        // the still-open window. The actual public surface is just the two
        // bootstrap endpoints: /challenge (initiate pairing) and /complete (finish
        // it with the PIN). Everything else, including revoke + devices, must
        // require a valid bearer token.
        // #190-HIGH: /metrics used to be in the public allowlist, but it returns
        // the full statusProvider output (connectedParents, addresses,
        // pairedDeviceCount, pairingChallengeActive) AND SessionAuthority.listSessions
        // with an 8-char prefix of every active token. Telling an unauthenticated
        // LAN peer exactly when the pairing window opens + giving them a stable
        // identifier for each parent's session is far too much. /healthz stays
        // public (it returns only {status,ts}). /api/fingerprint also stays public
        // so a future parent can TOFU-pin the TLS cert before holding a token.
        val isPublic = uri == "/" ||
                uri == "/api/pairing/challenge" ||
                uri == "/api/pairing/complete" ||
                uri == "/api/session/issue" ||
                // #348: refresh redeem is authenticated by the refresh token itself (it must work
                // after the bearer has expired), so it bypasses the bearer gate — like /session/issue.
                // It is still mutation-gated (POST+Origin), TLS-or-loopback-gated, and rate-limited.
                uri == "/api/session/refresh" ||
                uri == "/api/fingerprint" ||
                uri == "/healthz"
        val isCastMediaEndpoint = uri == "/cast" ||
                uri == "/cast/snapshot" ||
                uri == "/cast/stream.mjpeg"
        val isCastAuthorized = isCastMediaEndpoint &&
                session.method == NanoHTTPD.Method.GET &&
                castTokenValidator?.invoke(queryParam(session, "cast"), clientAddress(session)) == true
        // Loopback is reachable by other apps. Only this process's memory-only proof
        // grants the child pairing screen local administration without a parent bearer.
        val isLoopback = isLoopbackClient(session)
        val isLocalAdmin = isLoopback && localAdminValidator?.invoke(
            session.headers[de.rochefort.childmonitor.security.LocalControlAuthority.HEADER],
        ) == true

        // #466: never expose the child-displayed PIN to a remote caller, and never accept or
        // return pairing secrets on the plaintext LAN listener. The challenge endpoint exists for
        // the in-process child admin screen; remote parents use a PIN already shown by that screen.
        val pairingEndpoint = when (uri) {
            "/api/pairing/challenge" -> PairingTransportPolicy.Endpoint.CHALLENGE
            "/api/pairing/complete" -> PairingTransportPolicy.Endpoint.COMPLETE
            "/api/session/issue" -> PairingTransportPolicy.Endpoint.ISSUE_TOKEN
            else -> null
        }
        if (pairingEndpoint != null &&
            !PairingTransportPolicy.isAllowed(
                pairingEndpoint, isTlsListener,
                if (pairingEndpoint == PairingTransportPolicy.Endpoint.CHALLENGE) isLocalAdmin else isLoopback,
            )) {
            authFailureListener?.invoke(uri, clientAddress(session) ?: "")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "application/json",
                """{"success":false,"error":"tls_required"}"""
            )
        }

        // #190-HIGH CSRF: state-changing endpoints (camera toggle/on/off,
        // pairing/revoke) must reject GET requests and unexpected Origin
        // headers so a browser on a malicious ad / phishing page can't drive
        // them from the user's network. The authenticated child admin UI uses GET.
        val isMutation = uri == "/api/camera/toggle" ||
                uri == "/api/camera/on" ||
                uri == "/api/camera/off" ||
                uri == "/api/snapshot/cadence" ||
                uri == "/api/soother" ||
                uri == "/api/nightlight" ||
                uri == "/api/hooks" ||
                uri == "/api/hooks/test" ||
                uri == "/api/alerts/config" ||
                uri == "/api/mqtt/config" ||
                uri == "/api/cast/session" ||
                uri == "/api/cast/revoke" ||
                uri == "/api/pairing/revoke" ||
                uri == "/api/pairing/complete" ||
                uri == "/api/session/issue" ||
                uri == "/api/session/rotate" ||
                uri == "/api/session/refresh-credential" ||
                uri == "/api/session/refresh"

        // Broker credentials and topology are sensitive configuration. Even with a bearer token,
        // never accept them over the legacy cleartext LAN listener.
        if (uri == "/api/mqtt/config" && !isTlsListener && !isLoopback) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "application/json",
                """{"success":false,"error":"tls_required"}""",
            )
        }
        // Grant issuance/revocation carries the full parent bearer and must use the pinned TLS
        // control plane. The media-only renderer URL itself stays cleartext for DLNA compatibility.
        if ((uri == "/api/cast/session" || uri == "/api/cast/revoke") &&
            !isTlsListener && !isLoopback
        ) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "application/json",
                """{"success":false,"error":"tls_required"}""",
            )
        }
        if (isMutation && !isLocalAdmin) {
            // Require POST (or PUT). Browsers won't issue cross-site fetches
            // with custom methods + Authorization headers under the default
            // CORS rules without an explicit preflight, which we never grant.
            val method = session.method
            if (method != NanoHTTPD.Method.POST && method != NanoHTTPD.Method.PUT) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                    "application/json",
                    """{"success":false,"error":"method_not_allowed","reason":"$uri requires POST"}"""
                ).apply { addHeader("Allow", "POST, PUT") }
            }
            // Origin allowlist: if Origin is present (i.e. cross-site browser
            // request) it must be from one of our own URLs. Native callers
            // (the parent app) don't set Origin so they pass through.
            val origin = session.headers["origin"]
            if (!origin.isNullOrBlank()) {
                // Authenticated child admin callers are already exempted above, so we
                // don't need to allowlist localhost here. Keep the check strict: Origin
                // must match the host the request was actually sent to. A LAN attacker
                // can forge Host+Origin together but still gets 401 from the bearer gate.
                val host = session.headers["host"] ?: ""
                val ok = origin == "http://$host" || origin == "https://$host"
                if (!ok) {
                    authFailureListener?.invoke(uri, clientAddress(session) ?: "")
                    return NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.FORBIDDEN,
                        "application/json",
                        """{"success":false,"error":"csrf","reason":"origin not allowed"}"""
                    )
                }
            }
        }

        if (!isPublic && !isLocalAdmin && !isCastAuthorized && tokenValidator != null) {
            val token = bearerToken(session)
            if (!tokenValidator.invoke(token)) {
                authFailureListener?.invoke(uri, clientAddress(session) ?: "")
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"success":false,"error":"unauthorized","reason":"missing or expired token"}"""
                ).apply {
                    addHeader("WWW-Authenticate", "Bearer realm=\"childmonitor\"")
                }
            }
        }
        return when (uri) {
            "/" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", indexPage())
            "/healthz" -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                """{"status":"ok","ts":${System.currentTimeMillis()}}"""
            )
            "/metrics" -> {
                val payload = metricsProvider?.invoke()?.toString() ?: """{"error":"metrics not available"}"""
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain; version=0.0.4", payload)
            }
            "/api/fingerprint" -> {
                val fp = fingerprintProvider?.invoke()
                val httpsPort = httpsPortProvider?.invoke()
                val audioTlsPort = audioTlsPortProvider?.invoke()
                val sb = StringBuilder("{")
                sb.append("\"sha256\":").append(if (fp != null) "\"$fp\"" else "null")
                if (httpsPort != null) sb.append(",\"httpsPort\":$httpsPort")
                if (audioTlsPort != null) sb.append(",\"audioTlsPort\":$audioTlsPort")
                sb.append("}")
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", sb.toString())
            }
            "/api/session/issue" -> {
                val issuer = tokenIssuer
                if (issuer == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"error":"token issuance not supported"}""")
                } else {
                    // Secrets do not belong in URLs: accept the pairing PIN and device metadata in
                    // headers so they cannot leak through request-target logging/history (#466).
                    val code = session.headers["x-pairing-code"]
                    val deviceId = session.headers["x-device-id"]
                    val deviceName = session.headers["x-device-name"]
                    if (code.isNullOrBlank() || deviceId.isNullOrBlank()) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"code and deviceId are required"}""")
                    } else {
                        val result = issuer.invoke(code, deviceId, deviceName)
                        if (result == null) {
                            authFailureListener?.invoke(uri, clientAddress(session) ?: "")
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", """{"success":false,"error":"invalid pairing code"}""")
                        } else {
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.toString())
                        }
                    }
                }
            }
            "/api/session/rotate" -> {
                // #298: rotate the caller's bearer token to a fresh one (same device, fresh TTL),
                // invalidating the old token. Token-gated (the central auth gate already validated
                // the current token before we got here); the rotator re-checks and swaps it.
                val rotator = tokenRotator
                if (rotator == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val result = rotator.invoke(bearerToken(session))
                    if (result == null) {
                        authFailureListener?.invoke(uri, clientAddress(session) ?: "")
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", """{"success":false,"error":"invalid_or_expired_token"}""")
                    } else {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.put("success", true).toString())
                    }
                }
            }
            "/api/session/refresh-credential" -> {
                // #348: mint a durable refresh credential for the authenticated caller's device.
                // Bearer-gated (the central gate already validated the token). TLS-or-loopback only —
                // never hand a long-lived credential to a cleartext LAN caller.
                val issuer = refreshCredentialIssuer
                if (issuer == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else if (!isTlsListener && !isLoopback) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "application/json", """{"success":false,"error":"tls_required"}""")
                } else {
                    val result = try {
                        issuer.invoke(bearerToken(session), session.headers["x-parent-device-id"])
                    } catch (_: IOException) {
                        return NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                            "application/json",
                            """{"success":false,"error":"credential_storage_unavailable"}""",
                        )
                    }
                    if (result == null) {
                        authFailureListener?.invoke(uri, clientAddress(session) ?: "")
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", """{"success":false,"error":"unauthorized"}""")
                    } else {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.put("success", true).toString())
                    }
                }
            }
            "/api/session/refresh" -> {
                // #348: redeem a refresh token (deviceId query + X-Refresh-Token header) for a fresh
                // bearer. NOT bearer-gated (works after the bearer expired); authenticated by the
                // refresh token. TLS-or-loopback only; rate-limited in the handler.
                val redeemer = refreshRedeemer
                if (redeemer == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else if (!isTlsListener && !isLoopback) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "application/json", """{"success":false,"error":"tls_required"}""")
                } else {
                    val deviceId = queryParam(session, "deviceId")
                    val refreshToken = session.headers["x-refresh-token"]
                    val result = redeemer.invoke(deviceId, refreshToken)
                    when {
                        result == null -> {
                            // #354: definitive invalid/revoked → 401 so the parent drops the credential + re-pairs.
                            authFailureListener?.invoke(uri, clientAddress(session) ?: "")
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", """{"success":false,"error":"invalid_refresh"}""")
                        }
                        result.optBoolean("rateLimited", false) -> {
                            // #354: transient rate-limit → 429 (distinct from 401) so the parent keeps the credential + backs off.
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "application/json", """{"success":false,"error":"rate_limited"}""")
                        }
                        else -> {
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", result.put("success", true).toString())
                        }
                    }
                }
            }
            "/api/status" -> {
                val payload = statusProvider.invoke().toString()
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", payload)
            }
            "/api/cast/session" -> {
                val result = castSessionIssuer?.invoke(bearerToken(session))
                if (result == null) {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.UNAUTHORIZED,
                        "application/json",
                        """{"success":false,"error":"unauthorized"}""",
                    )
                } else {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/json",
                        result.put("success", true).toString(),
                    ).apply { addHeader("Cache-Control", "no-store") }
                }
            }
            "/api/cast/revoke" -> {
                val revoked = castSessionRevoker?.invoke(bearerToken(session)) == true
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    org.json.JSONObject().put("success", true).put("revoked", revoked).toString(),
                ).apply { addHeader("Cache-Control", "no-store") }
            }
            "/cast" -> if (isCastAuthorized) {
                castViewerResponse(queryParam(session, "cast") ?: "")
            } else castUnauthorizedResponse()
            "/cast/snapshot" -> if (isCastAuthorized) castSnapshotResponse() else castUnauthorizedResponse()
            "/cast/stream.mjpeg" -> if (isCastAuthorized) {
                castStreamResponse(queryParam(session, "cast"), clientAddress(session))
            } else castUnauthorizedResponse()
            "/api/pairing/challenge" -> {
                val provider = pairingChallengeProvider
                if (provider == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"error":"Pairing challenge not supported"}""")
                } else {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", provider.invoke().toString())
                }
            }
            "/api/pairing/complete" -> {
                val handler = pairingCompleteHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"error":"Pairing completion not supported"}""")
                } else {
                    val code = session.headers["x-pairing-code"]
                    val deviceId = session.headers["x-device-id"]
                    val deviceName = session.headers["x-device-name"]
                    if (code.isNullOrBlank() || deviceId.isNullOrBlank()) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"code and deviceId are required"}""")
                    } else {
                        val result = handler.invoke(code, deviceId, deviceName)
                        val ok = result.optBoolean("success", false)
                        val status = if (ok) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.UNAUTHORIZED
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/pairing/devices" -> {
                val provider = pairedDevicesProvider
                if (provider == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"error":"Paired devices not supported"}""")
                } else {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", provider.invoke().toString())
                }
            }
            "/api/pairing/revoke" -> {
                val handler = pairingRevokeHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"error":"Pairing revoke not supported"}""")
                } else {
                    val deviceId = queryParam(session, "deviceId")
                    if (deviceId.isNullOrBlank()) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"deviceId is required"}""")
                    } else {
                        val removed = handler.invoke(deviceId)
                        val status = if (removed) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_FOUND
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", """{"success":$removed}""")
                    }
                }
            }
            "/api/camera/toggle" -> {
                val handler = cameraToggleHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"error":"Camera toggle not supported"}""")
                } else {
                    val newState = handler.invoke()
                    Log.i(TAG, "Camera toggled, new state: $newState")
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", """{"cameraEnabled":$newState}""")
                }
            }
            "/api/camera/on" -> {
                val status = statusProvider.invoke()
                if (!status.optBoolean("cameraEnabled", false)) {
                    cameraToggleHandler?.invoke()
                }
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", """{"cameraEnabled":true}""")
            }
            "/api/camera/off" -> {
                val status = statusProvider.invoke()
                if (status.optBoolean("cameraEnabled", true)) {
                    cameraToggleHandler?.invoke()
                }
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", """{"cameraEnabled":false}""")
            }
            "/api/snapshot/cadence" -> {
                // #287: adjust snapshot cadence. ?mode=ECO|BALANCED|NEAR_LIVE|AUTO sets the mode;
                // ?glanceMs=N forces near-live for N ms (active watching). Read state via /api/status.
                val handler = snapshotCadenceHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val mode = queryParam(session, "mode")
                    val glanceMs = queryParam(session, "glanceMs")?.toLongOrNull()
                    if (mode == null && glanceMs == null) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"mode_or_glanceMs_required"}""")
                    } else {
                        val result = handler.invoke(mode, glanceMs)
                        val status = if (result.optBoolean("success", true)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/soother" -> {
                // #311/#328: control the child-speaker soother. ?action=play&volume=0..1 with
                // sound=WHITE|PINK|BROWN (noise), TWINKLE|FRERE_JACQUES|MARY (synth melodies),
                // or BRAHMS (bundled public-domain English vocal recording)
                // [&durationMs=N sleep-timer], ?action=timer&durationMs=N, ?action=stop, or
                // ?action=volume&volume=0..1. State via /api/status.soother.
                val handler = sootherHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val action = queryParam(session, "action")
                    val sound = queryParam(session, "sound")
                    val volume = queryParam(session, "volume")?.toFloatOrNull()
                    val durationRaw = queryParam(session, "durationMs")
                    val durationMs = durationRaw?.toLongOrNull()
                    if (action.isNullOrBlank()) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"action_required"}""")
                    } else if (durationRaw != null && durationMs == null) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"invalid_duration"}""")
                    } else {
                        val result = handler.invoke(action, sound, volume, durationMs)
                        val status = if (result.optBoolean("success", true)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/nightlight" -> {
                // #312/#490: control the child night-light. ?on=true|false&color=amber|#RRGGBB&
                // brightness=0..1&durationMs=N (any subset). Read state via /api/status.nightLight.
                val handler = nightLightHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val on = queryParam(session, "on")
                    val color = queryParam(session, "color")
                    val brightness = queryParam(session, "brightness")?.toFloatOrNull()
                    val durationRaw = queryParam(session, "durationMs")
                    val durationMs = durationRaw?.toLongOrNull()
                    if (on == null && color == null && brightness == null && durationRaw == null) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"on_color_brightness_or_duration_required"}""")
                    } else if (durationRaw != null && durationMs == null) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"invalid_duration"}""")
                    } else {
                        val result = handler.invoke(on, color, brightness, durationMs)
                        val status = if (result.optBoolean("success", true)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/hooks" -> {
                // #308: configure event hooks. ?enabled=true|false&webhookUrl=http://... (blank/none
                // clears the URL). #332: &lanOnly=true|false restricts webhook egress to private
                // addresses. Read state via /api/status.hooks.
                val handler = hooksHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val enabled = queryParam(session, "enabled")
                    val url = queryParam(session, "webhookUrl")
                    val lanOnly = queryParam(session, "lanOnly")
                    if (enabled == null && url == null && lanOnly == null) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"enabled_or_webhookUrl_required"}""")
                    } else {
                        val result = handler.invoke(enabled, url, lanOnly)
                        val status = if (result.optBoolean("success", true)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/hooks/test" -> {
                // #308: fire a synthetic test event to verify hooks are wired (requires enabled).
                val handler = hooksTestHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val result = handler.invoke()
                    val status = if (result.optBoolean("success", true)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                    NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                }
            }
            "/api/mqtt/config" -> {
                val handler = mqttConfigHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    // Headers keep the broker password and topology out of URLs, proxy history,
                    // access logs, and exception messages. Only known headers are copied.
                    val request = JSONObject()
                    listOf(
                        "enabled", "server", "username", "password-action", "password",
                        "base-topic", "temperature-topic", "humidity-topic",
                        "temperature-min", "temperature-max", "humidity-min", "humidity-max",
                    ).forEach { key ->
                        session.headers["x-cm-mqtt-$key"]?.let { request.put(key, it) }
                    }
                    if (request.length() == 0) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"no_params"}""")
                    } else {
                        val result = handler.invoke(request)
                        val status = if (result.optBoolean("success", false)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/alerts/config" -> {
                // #291: per-trigger alert toggles + quiet hours.
                // ?cry=true|false&cough=true|false&motion=true|false&quiet=true|false&quietStart=HH:MM&quietEnd=HH:MM
                // &floor=0..1 (any subset). Read state via /api/status.alertConfig.
                val handler = alertConfigHandler
                if (handler == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val cry = queryParam(session, "cry")
                    val cough = queryParam(session, "cough")
                    val motion = queryParam(session, "motion")
                    val quiet = queryParam(session, "quiet")
                    val quietStart = queryParam(session, "quietStart")
                    val quietEnd = queryParam(session, "quietEnd")
                    val floor = queryParam(session, "floor")
                    if (cry == null && cough == null && motion == null && quiet == null && quietStart == null && quietEnd == null && floor == null) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", """{"success":false,"error":"no_params"}""")
                    } else {
                        val result = handler.invoke(cry, cough, motion, quiet, quietStart, quietEnd, floor)
                        val status = if (result.optBoolean("success", true)) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.BAD_REQUEST
                        NanoHTTPD.newFixedLengthResponse(status, "application/json", result.toString())
                    }
                }
            }
            "/api/replay/audio" -> {
                // #284: last N seconds (default 30) of buffered audio for post-alert replay,
                // as G.711 mu-law 8 kHz mono (audio/basic). Token-gated like /snapshot.
                val provider = replayAudioProvider
                if (provider == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    // #381: clamp at the HTTP boundary (1..60s) so the invariant holds regardless of
                    // which provider is wired — AudioReplayBuffer computes seconds*bytesPerSecond,
                    // which would overflow Int for an absurd value if a future provider didn't clamp.
                    val seconds = (queryParam(session, "seconds")?.toIntOrNull() ?: 30).coerceIn(1, 60)
                    val audio = provider.invoke(seconds)
                    if (audio.isEmpty()) {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "audio/basic", "")
                    } else {
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            "audio/basic",
                            audio.inputStream(),
                            audio.size.toLong()
                        )
                    }
                }
            }
            "/api/replay/snapshots" -> {
                // #337: snapshot half of post-alert replay. No param → JSON list of buffered frame
                // timestamps (newest within the staleness window); ?at=<ts> → that frame as
                // image/jpeg. Token-gated like /snapshot + /api/replay/audio.
                val listProvider = replaySnapshotTimestampsProvider
                val frameProvider = replaySnapshotFrameProvider
                if (listProvider == null || frameProvider == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "application/json", """{"success":false,"error":"not_supported"}""")
                } else {
                    val at = queryParam(session, "at")?.toLongOrNull()
                    if (at != null) {
                        val jpeg = frameProvider.invoke(at)
                        if (jpeg == null || jpeg.isEmpty()) {
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "image/jpeg", "")
                        } else {
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
                        }
                    } else {
                        val timestamps = listProvider.invoke()
                        if (timestamps.isEmpty()) {
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "application/json", "")
                        } else {
                            val arr = org.json.JSONArray()
                            timestamps.forEach { arr.put(it) }
                            val body = org.json.JSONObject().put("timestamps", arr).put("count", timestamps.size).toString()
                            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body)
                        }
                    }
                }
            }
            "/snapshot" -> {
                val jpeg = snapshotProvider.invoke()
                if (jpeg == null) {
                    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", "No frame yet")
                } else {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "image/jpeg",
                        jpeg.inputStream(),
                        jpeg.size.toLong()
                    )
                }
            }
            // #306: token-gated MJPEG (multipart/x-mixed-replace) so standard consumers (a browser,
            // Home Assistant, Frigate, a wall tablet that can send the Bearer header) can pull the live
            // feed instead of polling /snapshot. Same auth as /snapshot (the central bearer gate above),
            // so it exposes nothing new; capped by mjpegLimiter so it can't starve the worker pool.
            "/stream.mjpeg" -> {
                if (snapshotProvider.invoke() == null) {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Camera off or no frame yet")
                } else if (!mjpegLimiter.tryAcquire()) {
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "text/plain", "Too many concurrent streams")
                } else {
                    NanoHTTPD.newChunkedResponse(
                        NanoHTTPD.Response.Status.OK,
                        MjpegFraming.CONTENT_TYPE,
                        MjpegFrameStream(snapshotProvider, mjpegLimiter::release),
                    ).apply {
                        addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        addHeader("Pragma", "no-cache")
                    }
                }
            }
            else -> super.serve(session)
        }
    }

    private fun queryParam(session: NanoHTTPD.IHTTPSession, name: String): String? {
        return session.parameters[name]?.firstOrNull()
    }

    private fun bearerToken(session: NanoHTTPD.IHTTPSession): String? {
        // #190-HIGH: ONLY accept the Authorization header. Previously we fell
        // back to ?token=/?access_token= query parameters, but those land in
        // proxy access logs, server access logs, browser history, and most
        // importantly the AuditLog itself when EVT_AUTH_FAIL records the
        // request URI. The parent app and the WS upgrade in ListenActivity
        // both set Authorization, so removing the query-string fallback
        // does not break any current client.
        val header = session.headers["authorization"] ?: session.headers["Authorization"]
        if (!header.isNullOrBlank()) {
            val trimmed = header.trim()
            if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
                return trimmed.substring(7).trim()
            }
        }
        return null
    }

    private fun castUnauthorizedResponse(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            "text/plain",
            "Cast session expired or unavailable",
        ).apply { addCastPrivacyHeaders() }

    private fun castViewerResponse(token: String): NanoHTTPD.Response {
        // Token shape was validated before this route. It is hex-only, so embedding it in an HTML
        // attribute cannot break out; still fail closed if this method is ever called independently.
        if (!de.rochefort.childmonitor.cast.CastSessionAuthority.isTokenShapeValid(token)) {
            return castUnauthorizedResponse()
        }
        val viewerText = castViewerTextProvider?.invoke()
        val title = htmlEscape(viewerText?.first.orEmpty().take(200))
        val caption = htmlEscape(viewerText?.second.orEmpty().take(300))
        val page = """
            <!doctype html>
            <html><head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <meta name="referrer" content="no-referrer">
              <title>$title</title>
              <style>
                html,body{height:100%;margin:0;background:#000;color:#fff;font-family:sans-serif}
                main{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center}
                img{display:block;max-width:100%;max-height:calc(100% - 3rem);object-fit:contain}
                p{margin:.7rem;font-size:.9rem;opacity:.75}
              </style>
            </head><body><main>
              <img src="/cast/stream.mjpeg?cast=$token" alt="$title">
              <p>$caption</p>
            </main></body></html>
        """.trimIndent()
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", page)
            .apply { addCastPrivacyHeaders() }
    }

    private fun castSnapshotResponse(): NanoHTTPD.Response {
        val jpeg = snapshotProvider.invoke()
        val response = if (jpeg == null || jpeg.isEmpty()) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "Camera off or no frame yet",
            )
        } else {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "image/jpeg",
                jpeg.inputStream(),
                jpeg.size.toLong(),
            )
        }
        return response.apply { addCastPrivacyHeaders() }
    }

    private fun castStreamResponse(token: String?, clientAddress: String?): NanoHTTPD.Response {
        val response = if (snapshotProvider.invoke() == null) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "Camera off or no frame yet",
            )
        } else if (!mjpegLimiter.tryAcquire()) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.TOO_MANY_REQUESTS,
                "text/plain",
                "Too many concurrent streams",
            )
        } else {
            NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK,
                MjpegFraming.CONTENT_TYPE,
                MjpegFrameStream(
                    snapshotProvider,
                    mjpegLimiter::release,
                    stillAuthorized = {
                        castTokenValidator?.invoke(token, clientAddress) == true
                    },
                ),
            )
        }
        return response.apply { addCastPrivacyHeaders() }
    }

    private fun NanoHTTPD.Response.addCastPrivacyHeaders() {
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        addHeader("Pragma", "no-cache")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader(
            "Content-Security-Policy",
            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
        )
    }

    private fun htmlEscape(value: String): String = buildString(value.length + 16) {
        value.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(it)
            }
        }
    }

    private fun clientAddress(session: NanoHTTPD.IHTTPSession): String? = session.remoteIpAddress

    private fun isLoopbackClient(session: NanoHTTPD.IHTTPSession): Boolean {
        val address = session.remoteIpAddress ?: return false
        return address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1"
    }

    fun audioListenerCount(): Int = audioClients.size

    fun revokeDevice(deviceId: String) {
        audioClients.filter { it.deviceId == deviceId }.forEach { it.stopAudio() }
        talkbackClients.filter { it.deviceId == deviceId }.forEach { client ->
            Thread({ client.disconnectTransport() }, "revoked-talkback-close")
                .apply { isDaemon = true }.start()
        }
    }

    override fun stop() {
        audioClients.forEach { it.stopAudio() }
        super.stop()
    }

    fun broadcastAudio(data: ByteArray, length: Int) {
        if (audioClients.isEmpty() || length == 0) return
        val frame = data.copyOfRange(0, length)
        for (socket in audioClients) socket.offerAudio(frame)
    }

    inner class LoggingSocket(handshake: NanoHTTPD.IHTTPSession) : WebSocket(handshake) {
        private val path = handshake.uri
        private val clientId = connectionId.incrementAndGet()
        override fun onOpen() {
            Log.i(TAG, "Web socket client connected: $path #$clientId")
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {
            Log.i(TAG, "Web socket client closed: $path #$clientId reason=$reason")
        }

        override fun onMessage(message: WebSocketFrame) {
            // No-op: console is receive-only
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException) {
            Log.e(TAG, "Web socket exception for $path #$clientId", exception)
        }
    }

    inner class AudioSocket(handshake: NanoHTTPD.IHTTPSession) : WebSocket(handshake) {
        val deviceId = authenticatedDeviceIdProvider?.invoke(bearerToken(handshake))
        val clientId = connectionId.incrementAndGet()
        @Volatile private var writer: de.rochefort.childmonitor.audio.BoundedAudioWriter? = null

        fun offerAudio(frame: ByteArray) { writer?.offer(frame) }
        fun stopAudio() { writer?.close() }

        override fun onOpen() {
            writer = de.rochefort.childmonitor.audio.BoundedAudioWriter(
                writeFrame = { send(it) },
                // A WebSocket close frame takes NanoWSD's send lock, which a stalled write
                // already holds. Close the underlying stream to release the socket directly.
                closeTransport = { handshakeRequest.inputStream.close() },
            )
            Log.i(TAG, "Audio WebSocket connected #$clientId")
            audioClients.add(this)
            // Register before rechecking so a concurrent revocation either sees this socket,
            // or invalidates its bearer before this check. It cannot leave an orphan stream.
            if (authenticatedDeviceIdProvider != null && (deviceId == null ||
                authenticatedDeviceIdProvider.invoke(bearerToken(handshakeRequest)) != deviceId)) {
                writer?.close()
                return
            }
            audioListenersChangedHandler?.invoke()
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {
            Log.i(TAG, "Audio WebSocket closed #$clientId reason=$reason")
            writer?.close()
            if (audioClients.remove(this)) audioListenersChangedHandler?.invoke()
        }

        override fun onMessage(message: WebSocketFrame) {
            // No-op, this is a downstream-only audio channel
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException) {
            Log.e(TAG, "Audio WebSocket exception #$clientId", exception)
            writer?.close()
            if (audioClients.remove(this)) audioListenersChangedHandler?.invoke()
        }
    }

    /**
     * WebSocket for receiving talkback audio from parent devices.
     * Audio is received as binary mu-law data and played on the child's speaker.
     */
    inner class TalkbackSocket(handshake: NanoHTTPD.IHTTPSession) : WebSocket(handshake) {
        val deviceId = authenticatedDeviceIdProvider?.invoke(bearerToken(handshake))
        fun disconnectTransport() {
            try { handshakeRequest.inputStream.close() } catch (_: IOException) {}
        }
        val clientId = connectionId.incrementAndGet()
        override fun onOpen() {
            Log.i(TAG, "Talkback WebSocket connected #$clientId")
            talkbackClients.add(this)
            if (authenticatedDeviceIdProvider != null && (deviceId == null ||
                authenticatedDeviceIdProvider.invoke(bearerToken(handshakeRequest)) != deviceId)) {
                disconnectTransport()
            }
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {
            Log.i(TAG, "Talkback WebSocket closed #$clientId reason=$reason")
            talkbackClients.remove(this)
            talkbackStoppedHandler?.invoke(this)
        }

        override fun onMessage(message: WebSocketFrame) {
            // Receive audio data from parent and play it
            val audioData = message.binaryPayload
            if (audioData != null && audioData.isNotEmpty()) {
                if (audioData.size > AudioCodecDefines.FREQUENCY) {
                    close(WebSocketFrame.CloseCode.MessageTooBig, "talkback_frame_too_large", false)
                    return
                }
                talkbackAudioHandler?.invoke(this, audioData)
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException) {
            Log.e(TAG, "Talkback WebSocket exception #$clientId", exception)
            talkbackClients.remove(this)
            talkbackStoppedHandler?.invoke(this)
        }
    }

    private fun indexPage(): String {
        // Very small, dependency-free UI. The audio player uses the same mu-law decoder as the Android client.
        return """
            <!doctype html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                    body { font-family: system-ui, sans-serif; background: #0a1020; color: #e6ebff; margin: 0; padding: 24px; }
                    h1 { margin: 0 0 12px 0; font-size: 22px; letter-spacing: 0.5px; }
                    .card { background:#121a2f; border:1px solid #28324e; border-radius:12px; padding:16px; margin-bottom:14px; box-shadow:0 10px 40px rgba(0,0,0,0.25); }
                    button { background:#3e7bff; color:white; border:none; border-radius:8px; padding:10px 16px; font-weight:600; cursor:pointer; }
                    button:disabled { opacity:.5; cursor:not-allowed; }
                    img { max-width:100%; border-radius:12px; border:1px solid #28324e; }
                    pre { white-space:pre-wrap; word-break:break-word; background:#0f1628; padding:12px; border-radius:8px; border:1px solid #28324e; }
                    .inline { display:flex; gap:12px; align-items:center; }
                    .metric { font-variant-numeric:tabular-nums; }
                </style>
            </head>
            <body>
                <h1>Child Monitor Live</h1>
                <div class="card">
                    <div class="inline" style="justify-content:space-between">
                        <div>
                            <div class="metric" id="statusText">Audio idle</div>
                            <div class="metric" id="metricsText">--</div>
                        </div>
                        <div class="inline">
                            <button id="startAudio">Start Audio</button>
                            <button id="toggleAudio" disabled>Pause</button>
                        </div>
                    </div>
                    <div style="margin-top:8px; font-size:12px; opacity:.7;">Click Start Audio once (browser requirement). The page will auto-reconnect and stay live like the parent app.</div>
                </div>
                <div class="card">
                    <div class="inline" style="justify-content:space-between; margin-bottom:8px;">
                        <div>Latest snapshot</div>
                        <button id="refreshSnap">Refresh</button>
                    </div>
                    <img id="snapshot" alt="Camera preview"/>
                </div>
                <div class="card">
                    <div class="inline" style="justify-content:space-between; margin-bottom:8px;">
                        <div>Status JSON</div>
                        <button id="refreshStatus">Refresh</button>
                    </div>
                    <pre id="statusJson">{}</pre>
                </div>
                <script>
                    const SAMPLE_RATE = 8000;
                    const MAX_QUEUE_MS = 2000; // trim backlog beyond 2s to stay in sync
                    let audioCtx;
                    let processor;
                    const audioQueue = [];
                    let audioEnabled = true;
                    let totalFrames = 0;
                    let lastUpdate = performance.now();
                    let ws;
                    let userArmed = false;
                    let reconnectDelay = 2000;

                    function mulawDecodeSample(mu) {
                        mu = ~mu & 0xff;
                        const sign = mu & 0x80;
                        const exponent = (mu >> 4) & 0x07;
                        const mantissa = mu & 0x0f;
                        let sample = ((mantissa << 3) + 0x84) << (exponent + 2);
                        return sign ? -sample : sample;
                    }

                    function decodeMuLaw(buffer) {
                        const arr = new Int16Array(buffer.byteLength);
                        for (let i = 0; i < buffer.byteLength; i++) {
                            arr[i] = mulawDecodeSample(buffer[i]);
                        }
                        return arr;
                    }

                    function ensureAudioGraph() {
                        if (audioCtx) return;
                        audioCtx = new (window.AudioContext || window.webkitAudioContext)({sampleRate: SAMPLE_RATE});
                        processor = audioCtx.createScriptProcessor(1024, 1, 1);
                        processor.onaudioprocess = function(e) {
                            const output = e.outputBuffer.getChannelData(0);
                            output.fill(0);
                            if (!audioEnabled || audioQueue.length === 0) return;
                            const frame = audioQueue.shift();
                            const len = Math.min(output.length, frame.length);
                            for (let i = 0; i < len; i++) {
                                output[i] = frame[i] / 32768;
                            }
                        };
                        processor.connect(audioCtx.destination);
                    }

                    function trimQueue() {
                        const maxSamples = MAX_QUEUE_MS * SAMPLE_RATE / 1000;
                        let queued = 0;
                        for (let i = 0; i < audioQueue.length; i++) queued += audioQueue[i].length;
                        if (queued <= maxSamples) return;
                        let toDrop = queued - maxSamples;
                        let dropped = 0;
                        while (audioQueue.length && toDrop > 0) {
                            const first = audioQueue[0];
                            if (first.length <= toDrop) {
                                dropped += first.length;
                                toDrop -= first.length;
                                audioQueue.shift();
                            } else {
                                const remain = first.subarray(first.length - (first.length - toDrop));
                                dropped += toDrop;
                                audioQueue[0] = remain;
                                toDrop = 0;
                            }
                        }
                        const droppedMs = Math.round((dropped / SAMPLE_RATE) * 1000);
                        document.getElementById('statusText').textContent = `Audio live (dropped ${'$'}{droppedMs}ms backlog)`;
                    }

                    function connectAudioWs() {
                        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
                        ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/audio');
                        ws.binaryType = 'arraybuffer';
                        ws.onmessage = (ev) => {
                            const ulaw = new Uint8Array(ev.data);
                            audioQueue.push(decodeMuLaw(ulaw));
                            trimQueue();
                            totalFrames++;
                            const now = performance.now();
                            if (now - lastUpdate > 1000) {
                                document.getElementById('metricsText').textContent = `${'$'}{totalFrames} frames received`;
                                lastUpdate = now;
                                totalFrames = 0;
                            }
                        };
                        ws.onopen = () => {
                            document.getElementById('statusText').textContent = 'Audio connected';
                            document.getElementById('toggleAudio').disabled = false;
                            reconnectDelay = 2000;
                        };
                        ws.onclose = () => {
                            document.getElementById('statusText').textContent = 'Audio disconnected';
                            document.getElementById('toggleAudio').disabled = true;
                            if (userArmed) {
                                setTimeout(connectAudioWs, reconnectDelay);
                                reconnectDelay = Math.min(reconnectDelay * 2, 30000);
                            }
                        };
                    }

                    document.getElementById('startAudio').onclick = async () => {
                        userArmed = true;
                        ensureAudioGraph();
                        await audioCtx.resume();
                        connectAudioWs();
                        document.getElementById('statusText').textContent = 'Audio starting...';
                    };

                    document.getElementById('toggleAudio').onclick = () => {
                        audioEnabled = !audioEnabled;
                        document.getElementById('toggleAudio').textContent = audioEnabled ? 'Pause' : 'Resume';
                    };

                    function refreshSnapshot() {
                        const img = document.getElementById('snapshot');
                        img.src = '/snapshot?t=' + Date.now();
                    }
                    function refreshStatus() {
                        fetch('/api/status').then(r => r.json()).then(json => {
                            document.getElementById('statusJson').textContent = JSON.stringify(json, null, 2);
                        }).catch(() => {});
                    }
                    document.getElementById('refreshSnap').onclick = refreshSnapshot;
                    document.getElementById('refreshStatus').onclick = refreshStatus;
                    refreshSnapshot();
                    refreshStatus();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "WebControlServer"

        /**
         * Max accepted inbound WebSocket frame size (#204). Talkback mu-law audio
         * frames are a few KB; 64 KiB is generous headroom while still rejecting
         * the 64-bit-length frames that are the only OOM vector. Any frame
         * declaring more is refused before allocation and the connection closed.
         */
        private const val MAX_WS_FRAME_BYTES = 64L * 1024

        /**
         * Max concurrent HTTP/WS connections (#207). Generous headroom over real
         * use: each parent holds at most a persistent /ws/audio + /ws/talkback,
         * plus a short-lived /snapshot poll (which lingers up to the 5 s keep-alive
         * read timeout), so a realistic deployment of a handful of parents sits
         * well under 64 and never hits the cap. It only bounds a connection flood
         * into a fixed thread ceiling. Note the WS client lists are not themselves
         * capped, so this 64 is the effective connection ceiling; a future per-IP
         * bucket (see BoundedRunCore) would add availability protection on top.
         */
        private const val MAX_CONCURRENT_HTTP_CONNECTIONS = 64
    }
}
