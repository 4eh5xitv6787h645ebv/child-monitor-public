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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * #362: a fixed WCAG 2.1 AA high-contrast palette applied as an overlay by ThemeManager when the
 * "High contrast" accessibility toggle is on, regardless of the chosen pastel theme. Pure ARGB Int
 * constants + the WCAG contrast-ratio formula, so the palette's AA compliance is unit-testable
 * (HighContrastColorsTest) without Android/Robolectric. ThemeManager builds its ThemePalette from these.
 *
 * Button text colour is auto-picked by ThemeManager from the button-background luminance (dark text on
 * light buttons, [textPrimary] on dark buttons), so [buttonPrimary]/[buttonAccent] are light (→ dark
 * text) and [buttonSecondary]/[buttonTalkIdle] are dark (→ white text) — both AA against their text.
 */
object HighContrastColors {

    val gradientStart = 0xFF000000.toInt()
    val gradientMid = 0xFF050505.toInt()
    val gradientEnd = 0xFF0A0A0A.toInt()

    val surfaceCard = 0xFF000000.toInt()
    val surfaceSoft = 0xFF1A1A1A.toInt()
    val surfaceBorder = 0xFFFFFFFF.toInt()

    val buttonPrimary = 0xFFFFFFFF.toInt()        // light → dark auto text
    val buttonSecondary = 0xFF1A1A1A.toInt()      // dark → white text + white border
    val buttonSecondaryBorder = 0xFFFFFFFF.toInt()
    val buttonAccent = 0xFFFFE000.toInt()         // bright amber, light → dark auto text
    val buttonTalkIdle = 0xFF1A1A1A.toInt()

    val textPrimary = 0xFFFFFFFF.toInt()
    val textSecondary = 0xFFF0F0F0.toInt()
    val textHint = 0xFFD0D0D0.toInt()

    // The decorative garden reduces contrast; make it fully transparent in high-contrast mode.
    val flowerColors = intArrayOf(0x00000000)

    /** The dark text ThemeManager places on a light button (kept in sync with applyRecursive's literal). */
    val onLightButtonText = 0xFF213126.toInt()

    /** WCAG 2.1 contrast ratio (1.0..21.0) between two opaque ARGB colours. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = max(la, lb)
        val darker = min(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        val r = linearize(((color shr 16) and 0xFF) / 255.0)
        val g = linearize(((color shr 8) and 0xFF) / 255.0)
        val b = linearize((color and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
