package de.rochefort.childmonitor.nighttimeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #283: pure-logic tests for the night-timeline capture pipeline. */
class NightTimelineLogicTest {

    // --- AudioLevel ---

    @Test fun silence_isFloor() {
        assertEquals(AudioLevel.FLOOR_DBFS, AudioLevel.rmsDbfs(ShortArray(160)), 0.0001)
        assertEquals(AudioLevel.FLOOR_DBFS, AudioLevel.rmsDbfs(ShortArray(0)), 0.0001)
    }

    @Test fun fullScaleTone_isNearZeroDbfs() {
        val s = ShortArray(1000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        // |±32767/8| square wave at full scale → ~0 dBFS
        assertTrue("expected near 0 dBFS, got ${AudioLevel.rmsDbfs(s)}", AudioLevel.rmsDbfs(s) > -0.5)
    }

    @Test fun quieterIsLowerDbfs() {
        val loud = ShortArray(1000) { if (it % 2 == 0) 8000 else -8000 }
        val quiet = ShortArray(1000) { if (it % 2 == 0) 800 else -800 }
        assertTrue(AudioLevel.rmsDbfs(quiet) < AudioLevel.rmsDbfs(loud))
        // 10x amplitude ≈ 20 dB louder
        assertEquals(20.0, AudioLevel.rmsDbfs(loud) - AudioLevel.rmsDbfs(quiet), 1.0)
    }

    // --- MinuteAggregator ---

    @Test fun noBucketUntilMinuteRollover() {
        val agg = MinuteAggregator()
        assertNull(agg.addLevel(-40.0, 0L))
        assertNull(agg.addLevel(-30.0, 30_000L))   // same minute
        val b = agg.addLevel(-50.0, 61_000L)        // next minute → emit minute 0
        assertNotNull(b)
        assertEquals(0L, b!!.minuteEpochMs)
        assertEquals(-30.0, b.rmsPeakDbfs, 0.0001)  // loudest in minute 0
        assertEquals(-40.0, b.rmsMinDbfs, 0.0001)   // quietest in minute 0
        assertEquals(-35.0, b.rmsMeanDbfs, 0.0001)  // mean of -40,-30
        assertTrue(b.hasAudio)
    }

    @Test fun motionMaxIsKept_andFlushEmitsPartial() {
        val agg = MinuteAggregator()
        agg.addLevel(-45.0, 5_000L)
        agg.addMotion(12, 6_000L)
        agg.addMotion(37, 7_000L)
        agg.addMotion(20, 8_000L)
        val b = agg.flush()
        assertNotNull(b)
        assertEquals(37, b!!.motion)
        assertEquals(0L, b.minuteEpochMs)
    }

    @Test fun flushWithNothing_returnsNull() {
        assertNull(MinuteAggregator().flush())
    }

    @Test fun minuteKeyIsFloored() {
        val agg = MinuteAggregator()
        agg.addLevel(-40.0, 125_000L)               // minute index 2 → key 120000
        val b = agg.addLevel(-40.0, 180_001L)
        assertEquals(120_000L, b!!.minuteEpochMs)
    }

    @Test fun backwardClockStep_doesNotRewindOrEmitPastMinute() {
        val agg = MinuteAggregator()
        agg.addLevel(-40.0, 120_000L)               // establish minute key 120000
        // Wall clock jumps backwards (NTP correction). Must NOT finalize a past minute.
        assertNull(agg.addLevel(-30.0, 5_000L))
        // Forward into the next real minute → emit the (still 120000) bucket once, with the
        // backward sample folded in (peak -30), never a separate stale row that could overwrite.
        val b = agg.addLevel(-50.0, 181_000L)
        assertNotNull(b)
        assertEquals(120_000L, b!!.minuteEpochMs)
        assertEquals(-30.0, b.rmsPeakDbfs, 0.0001)
    }

    // --- SleepState ---

    private fun bucket(peak: Double, motion: Int, audio: Boolean) =
        MinuteBucket(0L, peak, peak, peak, motion, audio)

    @Test fun noAudio_isNoSignal() {
        assertEquals(NightState.NO_SIGNAL, SleepState.classify(bucket(-20.0, 90, false), -40.0, 18))
    }

    @Test fun loudMinute_isNoisy() {
        assertEquals(NightState.NOISY, SleepState.classify(bucket(-30.0, 0, true), -40.0, 18))
    }

    @Test fun movementButQuiet_isAwake() {
        assertEquals(NightState.AWAKE, SleepState.classify(bucket(-60.0, 25, true), -40.0, 18))
    }

    @Test fun quietAndStill_isAsleep() {
        assertEquals(NightState.ASLEEP, SleepState.classify(bucket(-70.0, 3, true), -40.0, 18))
    }
}
