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

import java.util.Calendar

/**
 * #291: combines per-trigger toggles (cry on / cough off) with quiet-hours suppression into one
 * decision for whether a detected sound event should raise an alert. Pure + time-injected.
 *
 * Order: the trigger type must be enabled, the event must be intrinsically alert-worthy
 * ([SoundAlertPolicy]), and during a quiet-hours window only events at/above the escalation floor
 * get through ([QuietHoursRule.shouldEscalate]).
 */
object SoundAlertGate {

    fun shouldAlert(
        type: String,
        confidence: Double,
        cryEnabled: Boolean,
        coughEnabled: Boolean,
        motionEnabled: Boolean,
        quietHours: QuietHoursRule,
        calendar: Calendar = Calendar.getInstance()
    ): Boolean {
        val typeEnabled = when (type) {
            "cry" -> cryEnabled
            "cough" -> coughEnabled
            "motion" -> motionEnabled
            else -> false
        }
        if (!typeEnabled) return false
        val alertWorthy = if (type == "motion") confidence >= MOTION_CONFIDENCE_FLOOR
            else SoundAlertPolicy.isAlertWorthy(type, confidence)
        if (!alertWorthy) return false
        return quietHours.shouldEscalate(confidence, calendar)
    }

    /** Back-compatible field-wise overload for sound-only callers/tests. */
    fun shouldAlert(
        type: String,
        confidence: Double,
        cryEnabled: Boolean,
        coughEnabled: Boolean,
        quietHours: QuietHoursRule,
        calendar: Calendar = Calendar.getInstance()
    ): Boolean = shouldAlert(
        type, confidence, cryEnabled, coughEnabled, false, quietHours, calendar
    )

    /** Convenience overload taking the bundled [AlertConfig]. */
    fun shouldAlert(
        type: String,
        confidence: Double,
        config: AlertConfig,
        calendar: Calendar = Calendar.getInstance()
    ): Boolean = shouldAlert(
        type, confidence, config.cryEnabled, config.coughEnabled, config.motionEnabled,
        config.quietHours, calendar
    )

    /** Parse "HH:MM" (24h) → minutes-of-day pair, or null if malformed/out of range. */
    fun parseHhMm(raw: String?): Pair<Int, Int>? {
        val parts = raw?.trim()?.split(":") ?: return null
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private const val MOTION_CONFIDENCE_FLOOR = 0.18
}
