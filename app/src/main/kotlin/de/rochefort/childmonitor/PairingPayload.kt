/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * URL-style payload encoded into the child-side QR. Format:
 *   childmonitor://discover?id=<deviceId>&name=<friendly>&host=<ip>&port=<port>&v=2
 *   [&token=<bearer>&fp=<sha256>&hp=<httpsPort>&ap=<tlsAudioPort>]
 *
 * The `host` is the child's IP at the time the QR was generated. If the child later
 * roams to a new network, the parent falls back to NSD discovery filtered by `id`.
 *
 * Pass-5 MED-1: parser is implemented over java.net.URLDecoder rather than
 * android.net.Uri so it is unit-testable on the JVM without Robolectric.
 * The Android Uri class is unmocked under returnDefaultValues=true and any
 * test exercising parse() would NPE before reaching the validation code.
 */
data class PairingPayload(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    /**
     * Opaque bearer token issued by the child's SessionAuthority at QR generation
     * time. The parent stores this against [deviceId] and presents it as
     * `Authorization: Bearer <token>` on every HTTP / WebSocket request so the
     * child's WebControlServer can authenticate it. May be null for legacy QRs
     * generated before #193 was fixed; the parent treats those as "no token
     * available" and proceeds in best-effort mode (callbacks will 401).
     */
    val token: String? = null,
    /**
     * SHA-256 fingerprint (colon-separated uppercase hex) of the child's
     * self-signed TLS cert at QR generation time. The parent uses this for
     * trust-on-first-use pinning — refusing any later TLS handshake whose leaf
     * cert doesn't match. Null when the child couldn't generate a cert (e.g.
     * pre-API-23 device), in which case the parent stays on plaintext HTTP.
     */
    val fingerprint: String? = null,
    /**
     * Port the child's HTTPS listener is bound to. Null when TLS is unavailable.
     * When non-null, the parent prefers `https://host:httpsPort` over plaintext.
     */
    val httpsPort: Int? = null,
    /**
     * Port of the child's pinned-TLS audio listener. Its presence is also the
     * rollout marker: legacy pairings without this field may keep using the
     * original cleartext audio port, while pairings that receive it must never
     * send their bearer token over cleartext.
     */
    val audioTlsPort: Int? = null,
    /** Wire version. Version 2 requires the complete durable TLS pairing tuple. */
    val version: Int = CURRENT_VERSION,
) {
    fun isDurableSecure(): Boolean =
        version >= CURRENT_VERSION && !token.isNullOrBlank() && !fingerprint.isNullOrBlank() &&
            httpsPort != null && audioTlsPort != null

    fun encode(): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val sb = StringBuilder("childmonitor://discover?id=${enc(deviceId)}&name=${enc(name)}&host=${enc(host)}&port=$port&v=$version")
        if (!token.isNullOrBlank()) sb.append("&token=").append(enc(token))
        if (!fingerprint.isNullOrBlank()) sb.append("&fp=").append(enc(fingerprint))
        if (httpsPort != null) sb.append("&hp=").append(httpsPort)
        if (audioTlsPort != null) sb.append("&ap=").append(audioTlsPort)
        return sb.toString()
    }

    companion object {
        // Canonical CRLF/header-injection guards live in PairingValidation (one source of truth shared
        // with PairedChild.fromJson, so persisted/restored pairings get the SAME checks — #310/#423).
        // SAFE_TOKEN: SessionAuthority mints 64-char hex; the charset is a bit wider (URL-safe Base64 /
        // ASCII ids) but rejects anything that could header-inject the raw WS upgrade. SAFE_FP: a
        // SHA-256 fingerprint (32 colon-separated hex groups). SAFE_HOST: the `host` is interpolated
        // verbatim into "Host: $address:$wsPort\r\n" (no CRLF stripping), so a host failing it rejects
        // the whole payload (fail-closed) like a missing host.
        private val SAFE_TOKEN = PairingValidation.SAFE_TOKEN
        private val SAFE_FP = PairingValidation.SAFE_FINGERPRINT
        private val SAFE_HOST = PairingValidation.SAFE_HOST
        private const val SCHEME_PREFIX = "childmonitor://"
        const val CURRENT_VERSION = 2

        fun parse(raw: String): PairingPayload? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(SCHEME_PREFIX, ignoreCase = true)) return null
            // Strip URL fragment (anything after #) before parsing — matches
            // android.net.Uri.getQueryParameter semantics. Without this a QR
            // like ?id=x&host=h#anchor would leak the fragment into the last
            // value, leaving stray `#` in the unvalidated `host`/`name` fields.
            val noFragment = trimmed.substringBefore('#')
            val afterScheme = noFragment.substring(SCHEME_PREFIX.length)
            // After scheme we expect "<authority>?<query>". Authority is the
            // discover/pair path token; we don't validate it strictly because
            // historical QRs use both forms — we only need the query string.
            val qIdx = afterScheme.indexOf('?')
            val query = if (qIdx < 0) "" else afterScheme.substring(qIdx + 1)
            val params = parseQueryString(query)

            val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
            // #423: validate host against SAFE_HOST (see above) — a CRLF/control-char host would
            // otherwise header-inject into the raw WS upgrade. Reject the whole payload if invalid.
            val host = params["host"]?.takeIf { it.isNotBlank() && SAFE_HOST.matches(it) } ?: return null
            val port = params["port"]?.toIntOrNull() ?: 10000
            val name = params["name"]?.takeIf { it.isNotBlank() } ?: id
            // Validate the token format strictly. A malicious QR containing CRLF in
            // the token field could otherwise inject headers into the talkback
            // WebSocket upgrade. Drop the token (but keep the rest of the payload)
            // if it doesn't match the safe pattern.
            val token = params["token"]?.takeIf { it.isNotBlank() && SAFE_TOKEN.matches(it) }
            // Normalise to uppercase so later equality comparisons against the
            // server's computed fingerprint are case-insensitive without a per-check
            // .equals(ignoreCase=true) — keeps the pin check a simple ==.
            val fp = params["fp"]?.takeIf { it.isNotBlank() && SAFE_FP.matches(it) }?.uppercase()
            val httpsPort = params["hp"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            val audioTlsPort = params["ap"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            val version = params["v"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            return PairingPayload(
                deviceId = id, name = name, host = host, port = port,
                token = token, fingerprint = fp, httpsPort = httpsPort,
                audioTlsPort = audioTlsPort, version = version
            )
        }

        private fun parseQueryString(q: String): Map<String, String> {
            if (q.isEmpty()) return emptyMap()
            val out = mutableMapOf<String, String>()
            for (pair in q.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val k = if (eq < 0) pair else pair.substring(0, eq)
                val v = if (eq < 0) "" else pair.substring(eq + 1)
                val decodedKey = try { URLDecoder.decode(k, "UTF-8") } catch (_: Exception) { continue }
                val decodedVal = try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { "" }
                // First occurrence wins to match Uri.getQueryParameter semantics
                // (otherwise an attacker could append &id=different to override).
                if (!out.containsKey(decodedKey)) out[decodedKey] = decodedVal
            }
            return out
        }
    }
}
