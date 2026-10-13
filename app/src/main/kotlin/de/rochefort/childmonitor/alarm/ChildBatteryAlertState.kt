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
package de.rochefort.childmonitor.alarm

import de.rochefort.childmonitor.util.BatteryTelemetryPolicy

/**
 * #301 (parent side): pure edge-triggered state for the "child battery low" alert. The parent
 * polls the child's battery periodically, so a naive "alert whenever low" would re-fire every
 * poll. This fires **once** when the child enters a low-and-not-charging state and re-arms only
 * when it recovers (rises above the threshold) or goes on charge — so plugging in, or a brief
 * dip, doesn't nag. No Android deps.
 */
class ChildBatteryAlertState(private val threshold: Int = BatteryTelemetryPolicy.LOW_THRESHOLD_PCT) {

    private var alerted = false

    /**
     * Feed the latest child reading. Returns true when the caller should post a low-battery alert
     * now. [levelPct] == -1 (unknown) is never low. Charging clears the alert latch.
     */
    fun onReading(levelPct: Int, charging: Boolean): Boolean {
        if (!BatteryTelemetryPolicy.isLow(levelPct, charging, threshold)) {
            alerted = false // recovered / charging / unknown → re-arm for the next genuine low
            return false
        }
        if (alerted) return false
        alerted = true
        return true
    }

    /** Forget the latch (e.g. on disconnect) so the next low reading alerts again. */
    fun reset() {
        alerted = false
    }
}
