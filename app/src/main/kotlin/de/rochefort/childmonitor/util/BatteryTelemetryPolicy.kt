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
package de.rochefort.childmonitor.util

/**
 * #301 (child side): pure helpers for exposing the child device's battery over /api/status so the
 * parent can warn before the camera phone dies mid-nap. Android-free for unit testing; the actual
 * BatteryManager reads happen in MonitorService.
 */
object BatteryTelemetryPolicy {

    const val LOW_THRESHOLD_PCT = 15

    /**
     * Low-battery when we have a *known* level (0..100) at/below [threshold] AND we're not
     * charging. Unknown level (e.g. -1 from an unsupported `BATTERY_PROPERTY_CAPACITY`) is never
     * "low" — we don't want to cry wolf when we can't read it.
     */
    fun isLow(levelPct: Int, charging: Boolean, threshold: Int = LOW_THRESHOLD_PCT): Boolean =
        levelPct in 0..threshold && !charging

    /** Normalise the level to 0..100, or -1 when unknown/garbage (the BatteryManager sentinel). */
    fun normalizeLevel(raw: Int): Int = if (raw in 0..100) raw else -1

    /**
     * Map the `BatteryManager.EXTRA_PLUGGED` value to a stable label. Values are the platform
     * constants (AC=1, USB=2, WIRELESS=4, DOCK=8); 0 = unplugged, -1 = couldn't read (sticky
     * broadcast missing), anything else = "other".
     */
    fun chargingSource(plugged: Int): String = when (plugged) {
        -1 -> "unknown"
        0 -> "none"
        1 -> "ac"        // BATTERY_PLUGGED_AC
        2 -> "usb"       // BATTERY_PLUGGED_USB
        4 -> "wireless"  // BATTERY_PLUGGED_WIRELESS
        8 -> "dock"      // BATTERY_PLUGGED_DOCK (API 33+)
        else -> "other"
    }
}
