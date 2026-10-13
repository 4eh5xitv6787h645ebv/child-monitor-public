/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor

/**
 * A validated host/port pair used by reconnect persistence and URL/header construction.
 *
 * Historically reconnect targets were persisted as `host:port` and read with `split(":")`.
 * That made every IPv6 target unusable even though pairing and NSD both accept IPv6. This type
 * gives the app one canonical representation (`[ipv6]:port`) while still reading the legacy
 * unbracketed form (`ipv6:port`) already present in backups/preferences.
 */
@ConsistentCopyVisibility
data class NetworkEndpoint private constructor(val host: String, val port: Int) {

    /** Canonical form for preferences, logs, and labels. */
    fun encoded(): String = "${hostForAuthority(host)}:$port"

    /** RFC-compatible URI / HTTP Host-header authority. */
    fun authority(): String = encoded()

    companion object {
        fun create(host: String?, port: Int): NetworkEndpoint? {
            val normalized = host?.trim()?.removeSurrounding("[", "]") ?: return null
            if (!PairingValidation.isSafeHost(normalized) || !PairingValidation.isValidPort(port)) {
                return null
            }
            return NetworkEndpoint(normalized, port)
        }

        /**
         * Parse canonical `[ipv6]:port`, ordinary `host:port`, and the legacy unbracketed
         * `ipv6:port` form. A host without a port is accepted only when [defaultPort] is valid.
         */
        fun parse(raw: String?, defaultPort: Int? = null): NetworkEndpoint? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.startsWith("[")) {
                val close = value.indexOf(']')
                if (close <= 1) return null
                val host = value.substring(1, close)
                val suffix = value.substring(close + 1)
                val port = when {
                    suffix.startsWith(":") -> suffix.substring(1).toIntOrNull()
                    suffix.isEmpty() -> defaultPort
                    else -> null
                } ?: return null
                return create(host, port)
            }

            val separator = value.lastIndexOf(':')
            if (separator < 0) {
                val port = defaultPort ?: return null
                return create(value, port)
            }

            // lastIndexOf deliberately supports the app's old `fd00::1234:10000` storage form.
            val host = value.substring(0, separator)
            val port = value.substring(separator + 1).toIntOrNull() ?: return null
            return create(host, port)
        }

        fun authority(host: String, port: Int): String? = create(host, port)?.authority()

        fun hostForAuthority(host: String): String =
            if (host.contains(':')) "[$host]" else host
    }
}
