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
package de.rochefort.childmonitor.accessibility

/**
 * #362: high-contrast colours for the Canvas chart views (NightTimelineView, MultiNightTrendsView,
 * VolumeView), used when the "High contrast" accessibility toggle is on. Those views paint hardcoded
 * hex that the push-based ThemeManager doesn't reach, so they pull these instead. Pure ARGB Ints, so
 * legibility (grid/text ≥ AA, fills ≥ 3:1 vs the black background) and the state colours being
 * mutually distinguishable are unit-testable via [HighContrastColors.contrastRatio].
 */
object HighContrastChartColors {

    val bg = 0xFF000000.toInt()
    val grid = 0xFFAAAAAA.toInt()        // gridlines — clearly visible on black, not overpowering
    val text = 0xFFFFFFFF.toInt()        // axis labels
    val line = 0xFFFFFFFF.toInt()        // mean / waveform stroke / last-point

    // Sleep-state lanes/bars — picked for distinct hues that all read clearly on black.
    val asleep = 0xFF00E5FF.toInt()      // cyan
    val awake = 0xFFFFD54F.toInt()       // amber
    val noisy = 0xFFFF5252.toInt()       // red
    val noSignal = 0xFF9E9E9E.toInt()    // gray

    // Secondary overlays.
    val envelope = 0xFF69F0AE.toInt()    // green (loudness envelope / waveform fill)
    val motion = 0xFFFFAB40.toInt()      // orange (motion bars)
    val threshold = 0xFFFF1744.toInt()   // bright red (awake-threshold dashed line)
    val baseline = 0xFFCCCCCC.toInt()    // light gray baseline

    /** Translucent grid for views that want a faint grid (alpha 0x55 over the grid colour). */
    val gridFaint = 0x55FFFFFF.toInt()
}
