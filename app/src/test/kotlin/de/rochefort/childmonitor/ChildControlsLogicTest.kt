package de.rochefort.childmonitor

import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChildControlsLogicTest {

    // ---- sliderPercent ----

    @Test fun `fraction maps to 0-100 percent`() {
        assertEquals(0, ChildControlsLogic.sliderPercent(0.0))
        assertEquals(50, ChildControlsLogic.sliderPercent(0.5))
        assertEquals(70, ChildControlsLogic.sliderPercent(0.7))
        assertEquals(100, ChildControlsLogic.sliderPercent(1.0))
    }

    @Test fun `out-of-range or non-finite fraction is NO_VALUE`() {
        assertEquals(ChildControlsLogic.NO_VALUE, ChildControlsLogic.sliderPercent(-1.0)) // the -1 sentinel
        assertEquals(ChildControlsLogic.NO_VALUE, ChildControlsLogic.sliderPercent(-0.01))
        assertEquals(ChildControlsLogic.NO_VALUE, ChildControlsLogic.sliderPercent(1.5))
        assertEquals(ChildControlsLogic.NO_VALUE, ChildControlsLogic.sliderPercent(Double.NaN))
        assertEquals(ChildControlsLogic.NO_VALUE, ChildControlsLogic.sliderPercent(Double.POSITIVE_INFINITY))
    }

    // ---- shouldSeed (the stale-stomp guard) ----

    @Test fun `seeds when not dragging, generation unchanged, value valid`() {
        assertTrue(ChildControlsLogic.shouldSeed(dragging = false, genAtFetch = 3, genNow = 3, percent = 42))
        assertTrue(ChildControlsLogic.shouldSeed(dragging = false, genAtFetch = 0, genNow = 0, percent = 0))
        assertTrue(ChildControlsLogic.shouldSeed(dragging = false, genAtFetch = 7, genNow = 7, percent = 100))
    }

    @Test fun `never seeds while dragging`() {
        assertFalse(ChildControlsLogic.shouldSeed(dragging = true, genAtFetch = 3, genNow = 3, percent = 42))
    }

    @Test fun `never seeds when a touch happened since the read began`() {
        // This is the core Codex fix: a status read that started before a drag must not land after it.
        assertFalse(ChildControlsLogic.shouldSeed(dragging = false, genAtFetch = 2, genNow = 4, percent = 42))
    }

    @Test fun `never seeds an absent value`() {
        assertFalse(ChildControlsLogic.shouldSeed(dragging = false, genAtFetch = 1, genNow = 1, percent = ChildControlsLogic.NO_VALUE))
        assertFalse(ChildControlsLogic.shouldSeed(dragging = false, genAtFetch = 1, genNow = 1, percent = 101))
    }

    // ---- minutesRemaining (round up) ----

    @Test fun `minutes remaining rounds up so a few seconds reads 1m not 0m`() {
        assertEquals(0L, ChildControlsLogic.minutesRemaining(0L))
        assertEquals(0L, ChildControlsLogic.minutesRemaining(-5L))
        assertEquals(1L, ChildControlsLogic.minutesRemaining(1L))
        assertEquals(1L, ChildControlsLogic.minutesRemaining(59_000L))
        assertEquals(1L, ChildControlsLogic.minutesRemaining(60_000L))
        assertEquals(2L, ChildControlsLogic.minutesRemaining(60_001L))
        assertEquals(12L, ChildControlsLogic.minutesRemaining(12L * 60_000L))
    }

    @Test fun `custom duration accepts one minute through one day`() {
        assertEquals(60_000L, ChildControlsLogic.durationFromMinutes(1))
        assertEquals(90L * 60_000L, ChildControlsLogic.durationFromMinutes(90))
        assertEquals(24L * 60L * 60_000L, ChildControlsLogic.durationFromMinutes(24 * 60))
        assertEquals(null, ChildControlsLogic.durationFromMinutes(null))
        assertEquals(null, ChildControlsLogic.durationFromMinutes(0))
        assertEquals(null, ChildControlsLogic.durationFromMinutes(24 * 60 + 1))
    }

    @Test fun `clock time resolves to the next occurrence`() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(zone).apply {
            set(2026, Calendar.JULY, 20, 20, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(30L * 60_000L, ChildControlsLogic.durationUntilClockTime(now, 21, 0, zone))
        assertEquals(23L * 60L * 60_000L, ChildControlsLogic.durationUntilClockTime(now, 19, 30, zone))
        assertEquals(null, ChildControlsLogic.durationUntilClockTime(now, 24, 0, zone))
    }
}
