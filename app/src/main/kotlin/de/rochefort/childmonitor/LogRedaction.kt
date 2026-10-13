package de.rochefort.childmonitor

import java.security.MessageDigest

/**
 * Redacts personally/network-identifying values before they are persisted to the
 * diagnostic log (#206). The diagnostic log can be exported and shared to a
 * support chat, so it must not carry the home WiFi name or the device's full IP.
 *
 * - [redactSsid] replaces the network name with a stable short hash, so the log
 *   can still distinguish "network changed" without revealing which network.
 * - [redactIp] masks the host octet of an IPv4 address (keeping the subnet, which
 *   is useful for diagnostics) and the interface portion of an IPv6 address.
 *
 * Pure functions so they are unit-testable and can be applied at the logging
 * boundary regardless of caller.
 */
object LogRedaction {

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun redactSsid(ssid: String): String {
        if (ssid.isBlank()) return ssid
        return "wifi#" + sha256Hex(ssid).take(8)
    }

    fun redactIp(value: String): String {
        val v = value.trim()

        // Bracketed IPv6, optionally with :port  ->  [<masked>]<:port?>
        if (v.startsWith("[")) {
            val close = v.indexOf(']')
            return if (close >= 2) "[" + maskV6(v.substring(1, close)) + "]" + v.substring(close + 1) else value
        }

        return when (v.count { it == ':' }) {
            // Bare IPv4 (or a non-IP that we leave untouched).
            0 -> maskV4(v) ?: value
            // host:port — mask the host only when it is an IPv4 literal; keep the port.
            1 -> maskV4(v.substringBefore(':'))?.let { "$it:${v.substringAfter(':')}" } ?: value
            // Bare IPv6 (IPv6 with a port is always bracketed, handled above).
            else -> maskV6(v)
        }
    }

    /** Mask an IPv4 host octet, or null if [s] is not a valid dotted quad. */
    private fun maskV4(s: String): String? {
        val m = IPV4.matchEntire(s) ?: return null
        if (m.groupValues.drop(1).any { it.toInt() !in 0..255 }) return null
        return "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}.x"
    }

    /** Keep the first hextet (routing-prefix hint), drop the rest. */
    private fun maskV6(s: String): String = s.substringBefore(":") + ":x"

    private fun sha256Hex(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
