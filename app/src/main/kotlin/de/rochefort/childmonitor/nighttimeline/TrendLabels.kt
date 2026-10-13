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

import kotlin.math.ceil

/**
 * #388: pure x-axis label-thinning math for [MultiNightTrendsView]. The date labels are centered
 * per night-slot, so at many nights and/or a high system font scale a label can grow wider than its
 * slot and collide with its neighbours. This computes a stride (label every k-th night) instead of
 * dropping labels asymmetrically. No Android dependencies, so it is unit-testable without Robolectric.
 */
object TrendLabels {

    /**
     * How many nights to step between drawn labels so that a centered label up to [maxLabelPx] wide
     * (plus a [gapPx] breathing gap) fits within the spacing of `stride` slots of [slotPx]. Returns
     * at least 1 (label every night) and is robust to non-positive inputs.
     */
    fun labelStride(slotPx: Float, maxLabelPx: Float, gapPx: Float = 0f): Int {
        if (slotPx <= 0f || maxLabelPx <= 0f) return 1
        val needed = maxLabelPx + gapPx.coerceAtLeast(0f)
        return ceil(needed / slotPx).toInt().coerceAtLeast(1)
    }

    /**
     * Whether night [index] (of [count]) should be labeled for the given [stride], anchored to the
     * most recent night (the last index) so it is always labeled — that night matters most and sits
     * at the right edge. With `stride <= 1` every night is labeled.
     */
    fun shouldLabel(index: Int, count: Int, stride: Int): Boolean {
        if (stride <= 1) return true
        return (count - 1 - index) % stride == 0
    }
}
