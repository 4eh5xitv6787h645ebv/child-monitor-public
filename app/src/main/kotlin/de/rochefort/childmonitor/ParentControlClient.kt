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
 */
package de.rochefort.childmonitor

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Parent→child control-plane client for the live session's remote features (night light #312,
 * soother #311/#328, alert config #291, event hooks #308) — the same things the child web console
 * drives. It reuses the EXACT transport the live [ListenActivity] controls use: the #203
 * no-downgrade endpoint rule (HTTPS+pin when ever-pinned, else cleartext), [PinnedHttp] for the
 * pinned TLS, and the bearer token from [ParentSessionStore]. [address]/[deviceId] are the active
 * session's. All calls block — run them off the UI thread.
 */
class ParentControlClient(
    private val context: Context,
    private val address: String,
    private val deviceId: String?,
) {

    private fun endpointUrl(path: String): String {
        val hasPinNow = ParentSessionStore.fingerprintFor(context, deviceId) != null
        val hasEverPinned = ParentSessionStore.hasEverPinned(context, deviceId)
        val httpsPort = ParentSessionStore.httpsPortFor(context, deviceId) ?: DEFAULT_HTTPS_PORT
        return buildUrl(hasPinNow, hasEverPinned, httpsPort, address, path)
    }

    /** POST a control endpoint (state-changing; CSRF-safe per the child's WebControlServer). */
    fun post(path: String): JSONObject? = request(path, "POST")

    /** POST sensitive settings in headers so credentials never enter a URL or access log. */
    fun postWithHeaders(path: String, headers: Map<String, String>): JSONObject? =
        request(path, "POST", headers)

    /** GET (e.g. /api/status) to read current child state. */
    fun get(path: String): JSONObject? = request(path, "GET")

    private fun request(
        path: String, method: String, headers: Map<String, String> = emptyMap(), retryAuth: Boolean = true,
    ): JSONObject? {
        // Defence-in-depth sink guard (security review): refuse to build a URL from an unsafe host,
        // mirroring the talkback path's isSafeHost chokepoint. Fails closed (null → caller toasts)
        // rather than relying on java.net.URL's parser to reject a malformed authority.
        if (!PairingValidation.isSafeHost(address)) return null
        var conn: HttpURLConnection? = null
        val requestToken = ParentSessionStore.get(context, deviceId)
        return try {
            conn = PinnedHttp.openPinned(context, endpointUrl(path), deviceId)
            requestToken?.let {
                conn.setRequestProperty("Authorization", "Bearer $it")
            }
            headers.forEach { (name, value) ->
                // Keep this API narrowly scoped: callers cannot replace auth, Host, or transport
                // headers. MQTT values are length-bounded again on the child.
                if (name.lowercase().startsWith("x-cm-mqtt-") && value.length <= 1024) {
                    conn.setRequestProperty(name, value)
                }
            }
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = method
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
            }
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else {
                // Drain errorStream so the socket returns to the keep-alive pool (review #9).
                try { conn.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                if (conn.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && retryAuth &&
                    de.rochefort.childmonitor.parent.SessionRenewer.recoverRejectedBearer(
                        context, deviceId, address, requestToken,
                    ) == de.rochefort.childmonitor.parent.SessionRenewer.RenewResult.RENEWED) {
                    conn.disconnect()
                    request(path, method, headers, retryAuth = false)
                } else null
            }
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_HTTPS_PORT = 11443
        const val CLEARTEXT_PORT = 11000

        /** URL-encode a query-parameter VALUE (e.g. a "#RRGGBB" colour or a webhook URL). */
        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        /**
         * Pure assembly of the control-plane URL under the #203 no-downgrade rule: HTTPS on the
         * pinned port when TLS is required, else cleartext on [CLEARTEXT_PORT]. Extracted from
         * [endpointUrl] so it is unit-testable without a [Context].
         */
        fun buildUrl(
            hasPinNow: Boolean,
            hasEverPinned: Boolean,
            httpsPort: Int,
            address: String,
            path: String,
        ): String = if (TransportPolicy.requireTls(hasPinNow, hasEverPinned)) {
            "https://${NetworkEndpoint.hostForAuthority(address)}:$httpsPort$path"
        } else {
            "http://${NetworkEndpoint.hostForAuthority(address)}:$CLEARTEXT_PORT$path"
        }
    }
}
