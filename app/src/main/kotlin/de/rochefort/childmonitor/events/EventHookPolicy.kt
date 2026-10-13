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

import java.net.InetAddress
import java.net.URI

/**
 * #308: pure helpers for the event-hook integration (broadcast intents + a local webhook so
 * power users can drive Tasker / home-automation on monitor events). Validates the user-supplied
 * webhook URL and builds the JSON payload — kept Android-free so it can be unit-tested.
 */
object EventHookPolicy {

    const val EVENT_TEST = "test"
    const val EVENT_PARENT_CONNECTED = "parent_connected"
    const val EVENT_PARENT_DISCONNECTED = "parent_disconnected"
    const val EVENT_SOUND = "sound"
    const val EVENT_CRY = "cry"
    const val EVENT_MOTION = "motion"

    /**
     * Accept only a well-formed absolute http/https URL (the user's own LAN automation endpoint).
     * Returns the trimmed URL or null so the caller can 400. Rejects other schemes (no file://,
     * intent://, etc.) and anything without a host.
     */
    fun validateWebhookUrl(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return try {
            val u = URI(s)
            if (u.host.isNullOrBlank()) null else s
        } catch (e: Exception) {
            null
        }
    }

    /**
     * #332: whether [address] is on a private/local network — loopback, link-local, RFC1918
     * site-local, the unspecified address, or an IPv6 unique-local (fc00::/7, which
     * [java.net.InetAddress.isSiteLocalAddress] does NOT cover). Used by the optional LAN-only
     * webhook egress restriction. java.net only, so it stays unit-testable.
     */
    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isAnyLocalAddress
        ) {
            return true
        }
        val bytes = address.address
        // IPv6 unique-local fc00::/7 → first byte 0xFC or 0xFD.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    /**
     * #332: under LAN-only, a host is allowed only if it resolved to at least one address and EVERY
     * resolved address is private (so a hostname that resolves to both a private and a public IP is
     * rejected). Resolving at dispatch time and checking the resolved IPs narrows DNS-rebind risk.
     */
    fun allPrivate(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all { isPrivateAddress(it) }

    /**
     * Build the webhook JSON body: `{"event":..,"ts":..,<details>}`. Hand-rolled (no org.json) so
     * it stays pure/unit-testable; all string values are JSON-escaped.
     */
    fun buildJsonPayload(event: String, tsMs: Long, details: Map<String, String>): String {
        val sb = StringBuilder("{")
        sb.append("\"event\":\"").append(escape(event)).append("\",")
        sb.append("\"ts\":").append(tsMs)
        for ((k, v) in details) {
            sb.append(",\"").append(escape(k)).append("\":\"").append(escape(v)).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
