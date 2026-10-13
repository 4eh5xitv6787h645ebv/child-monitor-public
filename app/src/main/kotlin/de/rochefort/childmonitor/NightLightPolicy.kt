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
package de.rochefort.childmonitor

/**
 * #312: pure parsing/clamping for the remote night-light (turn the child screen into a dim,
 * colour-adjustable lamp). Colours are ARGB ints (always opaque); brightness is window
 * brightness in [0,1]. Defaults to a dim warm amber (low blue light, sleep-friendly).
 */
object NightLightPolicy {

    const val DEFAULT_BRIGHTNESS = 0.15f
    val DEFAULT_COLOR = 0xFFFF7A18.toInt() // warm amber

    private val PRESETS: Map<String, Int> = mapOf(
        "amber" to 0xFFFF7A18.toInt(),
        "orange" to 0xFFFF7A18.toInt(),
        "warm" to 0xFFFF7A18.toInt(),
        "warmwhite" to 0xFFFFE4B5.toInt(),
        "red" to 0xFFFF2200.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "moon" to 0xFFBFC8E0.toInt(), // cool dim
        "pink" to 0xFFFF8FB1.toInt()
    )

    /** Names of the built-in presets (for discovery / API docs). */
    fun presetNames(): Set<String> = PRESETS.keys

    /**
     * Parse a preset name or hex (`#RGB`-style `#RRGGBB` / `#AARRGGBB`, `#` optional) into an
     * opaque ARGB int. Returns null on unrecognised input so callers can reject it (400).
     */
    fun parseColor(raw: String?): Int? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        PRESETS[s]?.let { return it }
        val hex = if (s.startsWith("#")) s.substring(1) else s
        return parseHex(hex)
    }

    private fun parseHex(raw: String): Int? {
        val h = raw.lowercase() // case-insensitive even if called without the parseColor pre-lowercase
        val normalized = when (h.length) {
            6 -> "ff$h"   // RRGGBB -> opaque
            8 -> h        // AARRGGBB (alpha forced opaque below anyway)
            else -> return null
        }
        if (!normalized.all { it.isDigit() || it in 'a'..'f' }) return null
        val v = normalized.toLongOrNull(16) ?: return null
        return (v.toInt()) or 0xFF000000.toInt() // force fully opaque; night-light is never see-through
    }

    /** Window brightness in [0,1]; NaN falls back to the default, out-of-range is clamped. */
    fun clampBrightness(v: Float): Float = if (v.isNaN()) DEFAULT_BRIGHTNESS else v.coerceIn(0f, 1f)
}
