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

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Pure helpers for turning 16-bit PCM into a relative-loudness level (dBFS).
 *
 * #283: we deliberately report dBFS (relative to digital full scale), NOT calibrated
 * dB SPL — you cannot get true SPL from a remote, uncalibrated mic, and no competitor
 * publishes a real SPL scale either. The timeline labels this "relative loudness".
 */
object AudioLevel {
    /** Floor returned for silence / empty input. Also the bottom of the timeline Y axis. */
    const val FLOOR_DBFS = -90.0
    private const val FULL_SCALE = 32768.0

    /** RMS of [len] 16-bit samples expressed in dBFS, clamped to [FLOOR_DBFS, 0]. */
    fun rmsDbfs(samples: ShortArray, len: Int = samples.size): Double {
        val n = len.coerceAtMost(samples.size)
        if (n <= 0) return FLOOR_DBFS
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / n)
        if (rms < 1.0) return FLOOR_DBFS
        return (20.0 * log10(rms / FULL_SCALE)).coerceIn(FLOOR_DBFS, 0.0)
    }
}
