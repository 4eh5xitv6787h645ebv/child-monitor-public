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
package de.rochefort.childmonitor.events

import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * #308: dispatches monitor events to two opt-in, local-only sinks so power users can drive home
 * automation:
 *  1. an Android broadcast Intent ([ACTION_EVENT]) — for Tasker / other local apps;
 *  2. a best-effort POST to a user-configured webhook URL — for Home Assistant etc.
 *
 * Disabled by default (no broadcast, no webhook) — the broadcast is intentionally implicit so
 * Tasker can receive it, which means any local app with a matching receiver can too; the events
 * carry only coarse status (e.g. "parent_connected"), and nothing fires until the user opts in.
 * The webhook URL is validated ([EventHookPolicy.validateWebhookUrl]) to http/https only.
 */
class EventHookDispatcher(private val context: Context) {

    @Volatile var enabled = false
    @Volatile var webhookUrl: String? = null
    // #332: when on, the webhook host is resolved at dispatch time and the POST is blocked unless
    // every resolved address is private/LAN — keeping event metadata on the local network. Default
    // off to preserve #308's permissive behavior (the URL is already parent-set + bearer/CSRF-gated).
    @Volatile var lanOnly = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-hooks").apply { isDaemon = true }
    }

    /**
     * Fire [event] to the enabled sinks. No-op when disabled.
     *
     * [details] go to the (targeted, user-chosen) webhook; [broadcastDetails] go to the implicit
     * broadcast that ANY local app can read — so sensitive fields (e.g. the parent IP) belong in
     * [details] only. Defaults to [details] for events with nothing sensitive.
     */
    fun dispatch(
        event: String,
        details: Map<String, String> = emptyMap(),
        broadcastDetails: Map<String, String> = details
    ) {
        if (!enabled) return
        val ts = System.currentTimeMillis()
        try {
            val intent = Intent(ACTION_EVENT).apply {
                putExtra("event", event)
                putExtra("ts", ts)
                broadcastDetails.forEach { (k, v) -> putExtra(k, v) }
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast failed", e)
        }
        val url = webhookUrl
        if (url != null) {
            val payload = EventHookPolicy.buildJsonPayload(event, ts, details)
            try {
                executor.execute { postWebhook(url, payload) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                Log.w(TAG, "webhook dropped — dispatcher shutting down")
            }
        }
        Log.i(TAG, "Dispatched event hook: $event (webhook=${url != null})")
    }

    private fun postWebhook(url: String, payload: String) {
        // #332: LAN-only egress. Resolve the host now and block unless every resolved address is
        // private. Runs on the off-thread event-hooks executor, so the synchronous resolve is fine.
        // (HttpURLConnection re-resolves on connect, so a tiny DNS-rebind window remains; a future
        // hardening could pin the validated IP. Fail closed: any resolve/parse error blocks the POST.)
        if (lanOnly && !hostResolvesToLan(url)) {
            Log.w(TAG, "Webhook POST blocked: LAN-only is on and the target is not a private address")
            return
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = false // don't chase redirects off the user's endpoint
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            Log.i(TAG, "Webhook POST -> ${conn.responseCode}")
        } catch (e: Exception) {
            Log.w(TAG, "Webhook POST failed: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** #332: resolve [url]'s host and return true only if every resolved address is private/LAN. */
    private fun hostResolvesToLan(url: String): Boolean = try {
        val host = java.net.URI(url).host?.removeSurrounding("[", "]")
        if (host.isNullOrBlank()) {
            false
        } else {
            EventHookPolicy.allPrivate(java.net.InetAddress.getAllByName(host).toList())
        }
    } catch (e: Exception) {
        Log.w(TAG, "LAN-only resolve failed for webhook host: ${e.message}")
        false // fail closed
    }

    fun shutdown() {
        try { executor.shutdownNow() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "EventHooks"
        const val ACTION_EVENT = "de.rochefort.childmonitor.EVENT"
    }
}
