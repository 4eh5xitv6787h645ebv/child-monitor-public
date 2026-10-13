package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Motion-threshold clamp for the detection settings (shared by ListenActivity + SettingsActivity). */
class DetectionPrefsTest {

    @Test
    fun clamp_belowMin_raisesToMin() {
        assertEquals(DetectionPrefs.MOTION_THRESHOLD_MIN, DetectionPrefs.clampThreshold(0))
        assertEquals(DetectionPrefs.MOTION_THRESHOLD_MIN, DetectionPrefs.clampThreshold(3))
        assertEquals(5, DetectionPrefs.clampThreshold(-10))
    }

    @Test
    fun clamp_inRange_unchanged() {
        assertEquals(5, DetectionPrefs.clampThreshold(5))
        assertEquals(18, DetectionPrefs.clampThreshold(18))
        assertEquals(40, DetectionPrefs.clampThreshold(40))
    }

    @Test
    fun clamp_aboveMax_loweredToMax() {
        assertEquals(DetectionPrefs.MOTION_THRESHOLD_MAX, DetectionPrefs.clampThreshold(41))
        assertEquals(40, DetectionPrefs.clampThreshold(100))
    }
}
