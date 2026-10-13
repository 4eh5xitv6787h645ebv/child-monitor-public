package de.rochefort.childmonitor.reliability

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonotonicClockTest {

    @Test fun `detectJump returns false for equal deltas`() {
        assertFalse(MonotonicClock.detectJump(wallDelta = 1000L, monoDelta = 1000L))
    }

    @Test fun `detectJump returns false for small drift`() {
        assertFalse(MonotonicClock.detectJump(wallDelta = 1000L, monoDelta = 1100L))
    }

    @Test fun `detectJump returns true when wall jumps forward`() {
        assertTrue(MonotonicClock.detectJump(wallDelta = 61_000L, monoDelta = 1000L))
    }

    @Test fun `detectJump returns true when wall jumps backward`() {
        assertTrue(MonotonicClock.detectJump(wallDelta = -60_000L, monoDelta = 500L))
    }

    @Test fun `custom tolerance is respected`() {
        assertFalse(MonotonicClock.detectJump(1_500L, 1_000L, toleranceMs = 1_000L))
        assertTrue(MonotonicClock.detectJump(3_500L, 1_000L, toleranceMs = 1_000L))
    }
}
