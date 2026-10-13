package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BedtimeTimerPolicyTest {
    @Test fun `duration is clearable and bounded to one night`() {
        assertNull(BedtimeTimerPolicy.clampDurationMs(null))
        assertNull(BedtimeTimerPolicy.clampDurationMs(0L))
        assertEquals(BedtimeTimerPolicy.MIN_DURATION_MS, BedtimeTimerPolicy.clampDurationMs(1L))
        assertEquals(90L * 60_000L, BedtimeTimerPolicy.clampDurationMs(90L * 60_000L))
        assertEquals(BedtimeTimerPolicy.MAX_DURATION_MS, BedtimeTimerPolicy.clampDurationMs(Long.MAX_VALUE))
    }

    @Test fun `remaining time is never negative`() {
        assertEquals(0L, BedtimeTimerPolicy.remainingMs(0L, 1_000L))
        assertEquals(0L, BedtimeTimerPolicy.remainingMs(999L, 1_000L))
        assertEquals(500L, BedtimeTimerPolicy.remainingMs(1_500L, 1_000L))
    }

    @Test fun `screen locks only when both independent features are off`() {
        assertFalse(BedtimeTimerPolicy.shouldLockScreen(nightLightOn = true, sootherPlaying = false))
        assertFalse(BedtimeTimerPolicy.shouldLockScreen(nightLightOn = false, sootherPlaying = true))
        assertFalse(BedtimeTimerPolicy.shouldLockScreen(nightLightOn = true, sootherPlaying = true))
        assertTrue(BedtimeTimerPolicy.shouldLockScreen(nightLightOn = false, sootherPlaying = false))
    }
}
