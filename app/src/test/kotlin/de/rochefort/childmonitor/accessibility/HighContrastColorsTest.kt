package de.rochefort.childmonitor.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #362: verify the high-contrast palette actually meets WCAG 2.1 AA (>=4.5:1 for normal text). */
class HighContrastColorsTest {

    private val AA = 4.5
    private val c = HighContrastColors

    private fun assertAA(name: String, fg: Int, bg: Int) {
        val r = c.contrastRatio(fg, bg)
        assertTrue("$name should be >= $AA:1 but was ${"%.1f".format(r)}:1", r >= AA)
    }

    // --- contrastRatio sanity (known reference values) ---

    @Test fun ratio_blackOnWhite_is21() {
        assertEquals(21.0, c.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.1)
    }

    @Test fun ratio_sameColor_is1() {
        assertEquals(1.0, c.contrastRatio(0xFF3366AA.toInt(), 0xFF3366AA.toInt()), 0.001)
    }

    @Test fun ratio_isSymmetric() {
        val a = c.contrastRatio(0xFF123456.toInt(), 0xFFEEDDCC.toInt())
        val b = c.contrastRatio(0xFFEEDDCC.toInt(), 0xFF123456.toInt())
        assertEquals(a, b, 0.0001)
    }

    // --- the actual palette must pass AA on every text-on-background pairing ---

    @Test fun primaryText_onSurfaces_passAA() {
        assertAA("primary/card", c.textPrimary, c.surfaceCard)
        assertAA("primary/soft", c.textPrimary, c.surfaceSoft)
        assertAA("primary/gradientEnd", c.textPrimary, c.gradientEnd)
    }

    @Test fun secondaryText_onSurfaces_passAA() {
        assertAA("secondary/card", c.textSecondary, c.surfaceCard)
        assertAA("secondary/soft", c.textSecondary, c.surfaceSoft)
    }

    @Test fun hintText_onSurfaces_passAA() {
        assertAA("hint/card", c.textHint, c.surfaceCard)
        assertAA("hint/soft", c.textHint, c.surfaceSoft)
    }

    @Test fun buttonText_passAA() {
        // Light buttons get dark auto text; dark buttons get white text.
        assertAA("darkText/primaryBtn", c.onLightButtonText, c.buttonPrimary)
        assertAA("darkText/accentBtn", c.onLightButtonText, c.buttonAccent)
        assertAA("whiteText/secondaryBtn", c.textPrimary, c.buttonSecondary)
        assertAA("whiteText/talkBtn", c.textPrimary, c.buttonTalkIdle)
    }

    @Test fun borderVisible_onCard() {
        // The border isn't text, but it must be clearly visible against the card (use AA as a strong bar).
        assertAA("border/card", c.surfaceBorder, c.surfaceCard)
    }
}
