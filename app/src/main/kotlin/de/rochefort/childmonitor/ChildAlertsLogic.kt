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

/**
 * Pure helpers for [ChildAlertsActivity] (alert config #291 + event hooks #308), factored out so the
 * HH:MM parsing, quiet-hours formatting, and webhook-URL sanity check are unit-testable without an
 * Activity / Context. The child remains the authority — these only shape the parent UI and avoid
 * sending obviously-malformed values.
 */
object ChildAlertsLogic {

    /** Parse a 24h "HH:MM" into (hour 0..23, minute 0..59), or null when malformed. */
    fun parseHhmm(value: String?): Pair<Int, Int>? {
        val s = value?.trim() ?: return null
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /** Format a clock time as zero-padded 24h "HH:MM" (clamped defensively to valid ranges). */
    fun formatHhmm(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    /**
     * A conservative client-side sanity check before sending a webhook URL: must be a well-formed
     * http/https URL with a non-blank host. NOT a security control — the child enforces lanOnly /
     * egress (#332); this only stops the parent from sending obvious junk (and an empty string is
     * treated as "clear the URL", handled by the caller, not here).
     */
    fun isLikelyWebhookUrl(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return false
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        return try {
            val host = java.net.URI(s).host
            !host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
