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
package de.rochefort.childmonitor.video

/**
 * #313: pure decision/aspect math for the parent "glance" picture-in-picture window. No Android
 * dependencies, so it is unit-testable without Robolectric; the Activity supplies the runtime facts
 * (PiP feature present, SDK level high enough, camera on, a frame in hand, not scrubbing) and turns
 * the clamped aspect ratio into an `android.util.Rational`.
 */
object PipPolicy {

    // Android requires a PiP aspect ratio strictly within (1/2.39 .. 2.39); clamp to those bounds so
    // setAspectRatio() can never throw on an unusual frame.
    private const val MAX_RATIO = 2.39
    private const val MIN_RATIO = 1.0 / 2.39
    private val FALLBACK = 4 to 3

    /**
     * Whether a PiP glance can be entered right now. PiP is an enhancement: it requires the device
     * feature and a high-enough SDK, and it only makes sense when the camera is on and a frame has
     * actually arrived (so the window isn't black), and never while scrubbing replay frames.
     */
    fun canEnter(
        hasPipFeature: Boolean,
        sdkSupportsPip: Boolean,
        cameraEnabled: Boolean,
        hasFrame: Boolean,
        scrubActive: Boolean,
    ): Boolean = hasPipFeature && sdkSupportsPip && cameraEnabled && hasFrame && !scrubActive

    /**
     * Clamp a frame's [width]x[height] to a PiP-legal aspect ratio, returned as a GCD-reduced integer
     * numerator/denominator pair (e.g. 1024x768 → 4:3). Non-positive dimensions fall back to 4:3.
     * Reducing keeps the Rational small, which a few OEM PiP implementations are happier with.
     */
    fun clampAspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return FALLBACK
        val ratio = width.toDouble() / height.toDouble()
        return when {
            ratio > MAX_RATIO -> 239 to 100   // 2.39:1 (already reduced)
            ratio < MIN_RATIO -> 100 to 239   // 1:2.39 (already reduced)
            else -> {
                val g = gcd(width, height)
                width / g to height / g
            }
        }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
