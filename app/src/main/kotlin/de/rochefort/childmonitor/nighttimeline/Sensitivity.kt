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
package de.rochefort.childmonitor.nighttimeline

import android.content.Context

/**
 * User sensitivity for the night timeline's noise/motion classification (#285) — the
 * single tunable that the whole market exposes and that, when omitted, draws complaints.
 *
 * HIGH = flag more (a quieter sound / smaller movement counts as awake/noisy) → a LOWER
 * dBFS threshold and a LOWER motion %. LOW = flag less (only loud/large). The night
 * timeline classifies at render time from this, so changing it instantly recolours the
 * whole night (no recapture needed); [NightRecorder] also seeds its live thresholds from it.
 */
enum class Sensitivity(val label: String, val awakeDbfs: Double, val motionThresholdPercent: Int) {
    LOW("Low", -30.0, 28),
    MEDIUM("Medium", -40.0, 18),
    HIGH("High", -50.0, 10);

    companion object {
        const val PREFS = "ListenPreferences"
        const val KEY = "nightSensitivity"
        val DEFAULT = MEDIUM

        fun fromName(name: String?): Sensitivity = entries.firstOrNull { it.name == name } ?: DEFAULT

        fun load(context: Context): Sensitivity =
            fromName(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun save(context: Context, s: Sensitivity) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s.name).apply()

        /** Cycle LOW → MEDIUM → HIGH → LOW for a one-tap selector. */
        fun next(s: Sensitivity): Sensitivity = entries[(s.ordinal + 1) % entries.size]
    }
}
