package de.rochefort.childmonitor.tracker

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerInputValidationTest {
    @Test fun `optional integer distinguishes blank from malformed and out of range`() {
        assertTrue(TrackerInputValidation.optionalInt("", 1..360))
        assertTrue(TrackerInputValidation.optionalInt(" 30 ", 1..360))
        assertFalse(TrackerInputValidation.optionalInt("0", 1..360))
        assertFalse(TrackerInputValidation.optionalInt("361", 1..360))
        assertFalse(TrackerInputValidation.optionalInt("1.5", 1..360))
    }

    @Test fun `required integer rejects blank and out of range`() {
        assertFalse(TrackerInputValidation.requiredInt("", 1..360))
        assertFalse(TrackerInputValidation.requiredInt("0", 1..360))
        assertTrue(TrackerInputValidation.requiredInt("360", 1..360))
        assertFalse(TrackerInputValidation.requiredInt("361", 1..360))
    }

    @Test fun `double validation rejects non finite and unrealistic values`() {
        val range = 0.0..2000.0
        assertTrue(TrackerInputValidation.optionalFiniteDouble("", range))
        assertTrue(TrackerInputValidation.optionalFiniteDouble("125.5", range))
        for (bad in listOf("NaN", "Infinity", "-Infinity", "-1", "2001", "abc")) {
            assertFalse(TrackerInputValidation.optionalFiniteDouble(bad, range), bad)
        }
    }

    @Test fun `required values and multi field emptiness are explicit`() {
        assertFalse(TrackerInputValidation.requiredFiniteDouble("", 0.0..1000.0))
        assertTrue(TrackerInputValidation.requiredFiniteDouble("0", 0.0..1000.0))
        assertFalse(TrackerInputValidation.anyEntered("", "  ", ""))
        assertTrue(TrackerInputValidation.anyEntered("", "2", ""))
    }
}
