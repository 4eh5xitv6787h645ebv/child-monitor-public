package de.rochefort.childmonitor.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #362: the high-contrast chart palette must be legible on black and have distinguishable states. */
class HighContrastChartColorsTest {

    private val c = HighContrastChartColors

    private fun ratioVsBg(color: Int) = HighContrastColors.contrastRatio(color, c.bg)

    @Test fun gridAndText_legibleOnBlack() {
        // Axis labels are text → AA (4.5); gridlines → at least the 3:1 graphical-object bar.
        assertTrue("text ${ratioVsBg(c.text)}", ratioVsBg(c.text) >= 4.5)
        assertTrue("line ${ratioVsBg(c.line)}", ratioVsBg(c.line) >= 4.5)
        assertTrue("grid ${ratioVsBg(c.grid)}", ratioVsBg(c.grid) >= 3.0)
    }

    @Test fun stateFills_visibleOnBlack() {
        // Sleep-state fills are graphical objects → ≥ 3:1 against the background to be perceivable.
        listOf(
            "asleep" to c.asleep, "awake" to c.awake, "noisy" to c.noisy, "noSignal" to c.noSignal,
            "envelope" to c.envelope, "motion" to c.motion, "threshold" to c.threshold,
        ).forEach { (name, color) ->
            assertTrue("$name ${ratioVsBg(color)}", ratioVsBg(color) >= 3.0)
        }
    }

    @Test fun stateColors_areMutuallyDistinct() {
        val states = listOf(c.asleep, c.awake, c.noisy, c.noSignal)
        assertEquals("the four states must be different colors", states.size, states.toSet().size)
    }

    @Test fun adjacentStateHues_areDistinguishable() {
        // The pairs most likely to be confused (asleep vs noSignal, awake vs noisy) must differ enough
        // that one isn't a near-shade of the other (contrast ratio between them well above 1).
        assertTrue(HighContrastColors.contrastRatio(c.asleep, c.noSignal) >= 1.5)
        assertTrue(HighContrastColors.contrastRatio(c.awake, c.noisy) >= 1.5)
    }

    @Test fun backgroundIsOpaqueBlack() {
        assertEquals(0xFF000000.toInt(), c.bg)
    }
}
