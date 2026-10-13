package de.rochefort.childmonitor.nighttimeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #285: pure-logic tests for night-timeline sensitivity. */
class SensitivityTest {

    @Test fun fromName_knownAndDefault() {
        assertEquals(Sensitivity.LOW, Sensitivity.fromName("LOW"))
        assertEquals(Sensitivity.HIGH, Sensitivity.fromName("HIGH"))
        assertEquals(Sensitivity.DEFAULT, Sensitivity.fromName(null))
        assertEquals(Sensitivity.DEFAULT, Sensitivity.fromName("garbage"))
        assertEquals(Sensitivity.MEDIUM, Sensitivity.DEFAULT)
    }

    @Test fun next_cycles() {
        assertEquals(Sensitivity.MEDIUM, Sensitivity.next(Sensitivity.LOW))
        assertEquals(Sensitivity.HIGH, Sensitivity.next(Sensitivity.MEDIUM))
        assertEquals(Sensitivity.LOW, Sensitivity.next(Sensitivity.HIGH))
    }

    @Test fun higherSensitivity_meansLowerThresholds() {
        // More sensitive = flag quieter sound / smaller movement = LOWER dBFS + LOWER motion %.
        assertTrue(Sensitivity.HIGH.awakeDbfs < Sensitivity.MEDIUM.awakeDbfs)
        assertTrue(Sensitivity.MEDIUM.awakeDbfs < Sensitivity.LOW.awakeDbfs)
        assertTrue(Sensitivity.HIGH.motionThresholdPercent < Sensitivity.MEDIUM.motionThresholdPercent)
        assertTrue(Sensitivity.MEDIUM.motionThresholdPercent < Sensitivity.LOW.motionThresholdPercent)
    }

    @Test fun sameMinuteClassifiesLouderAsSensitivityRises() {
        // A -45 dBFS peak with no motion: quiet for LOW/MEDIUM, "noisy" only at HIGH.
        val b = MinuteBucket(0L, -50.0, -47.0, -45.0, 0, hasAudio = true)
        fun cls(s: Sensitivity) = SleepState.classify(b, s.awakeDbfs, s.motionThresholdPercent)
        assertEquals(NightState.ASLEEP, cls(Sensitivity.LOW))
        assertEquals(NightState.ASLEEP, cls(Sensitivity.MEDIUM))
        assertEquals(NightState.NOISY, cls(Sensitivity.HIGH))
    }

    @Test fun motionGradient() {
        // 12% motion, quiet audio: still for LOW (thresh 28) & MEDIUM (18), AWAKE at HIGH (10).
        val b = MinuteBucket(0L, -70.0, -68.0, -66.0, 12, hasAudio = true)
        fun cls(s: Sensitivity) = SleepState.classify(b, s.awakeDbfs, s.motionThresholdPercent)
        assertEquals(NightState.ASLEEP, cls(Sensitivity.LOW))
        assertEquals(NightState.ASLEEP, cls(Sensitivity.MEDIUM))
        assertEquals(NightState.AWAKE, cls(Sensitivity.HIGH))
    }
}
