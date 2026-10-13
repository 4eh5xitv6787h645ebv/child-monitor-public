package de.rochefort.childmonitor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #311: soother sound parsing + volume clamping. */
class SootherPolicyTest {

    @Test fun parsesNamesAndAliasesCaseInsensitively() {
        assertEquals(SoothingSound.WHITE, SootherPolicy.parseSound("white"))
        assertEquals(SoothingSound.WHITE, SootherPolicy.parseSound(" White_Noise "))
        assertEquals(SoothingSound.PINK, SootherPolicy.parseSound("PINK"))
        assertEquals(SoothingSound.PINK, SootherPolicy.parseSound("pink-noise"))
        assertEquals(SoothingSound.BROWN, SootherPolicy.parseSound("brown"))
        assertEquals(SoothingSound.BROWN, SootherPolicy.parseSound("red")) // brown a.k.a. red noise
        assertEquals(SoothingSound.BRAHMS, SootherPolicy.parseSound("Brahms lullaby"))
        assertEquals(SoothingSound.BRAHMS, SootherPolicy.parseSound("wiegenlied"))
        assertEquals(true, SoothingSound.BRAHMS.bundledRecording)
    }

    @Test fun rejectsUnknownOrEmpty() {
        assertNull(SootherPolicy.parseSound("rain"))
        assertNull(SootherPolicy.parseSound(""))
        assertNull(SootherPolicy.parseSound(null))
    }

    @Test fun clampsVolumeIntoUnitRange() {
        assertEquals(0f, SootherPolicy.clampVolume(-0.5f), 0f)
        assertEquals(1f, SootherPolicy.clampVolume(2f), 0f)
        assertEquals(0.3f, SootherPolicy.clampVolume(0.3f), 0f)
    }

    @Test fun nanVolumeFallsBackToDefault() {
        assertEquals(SootherPolicy.DEFAULT_VOLUME, SootherPolicy.clampVolume(Float.NaN), 0f)
    }

    // #328 sleep-timer duration clamping.

    @Test fun nullOrNonPositiveDurationMeansNoTimer() {
        assertNull(SootherPolicy.clampDurationMs(null))
        assertNull(SootherPolicy.clampDurationMs(0L))
        assertNull(SootherPolicy.clampDurationMs(-5_000L))
    }

    @Test fun durationBelowMinIsRaisedToMin() {
        assertEquals(SootherPolicy.MIN_DURATION_MS, SootherPolicy.clampDurationMs(1_000L))
        assertEquals(SootherPolicy.MIN_DURATION_MS, SootherPolicy.clampDurationMs(SootherPolicy.MIN_DURATION_MS - 1))
    }

    @Test fun durationAboveMaxIsCappedToMax() {
        assertEquals(SootherPolicy.MAX_DURATION_MS, SootherPolicy.clampDurationMs(Long.MAX_VALUE))
        assertEquals(SootherPolicy.MAX_DURATION_MS, SootherPolicy.clampDurationMs(SootherPolicy.MAX_DURATION_MS + 1))
    }

    @Test fun durationInRangeIsUnchanged() {
        val thirtyMin = 30L * 60 * 1000
        assertEquals(thirtyMin, SootherPolicy.clampDurationMs(thirtyMin))
        assertEquals(SootherPolicy.MIN_DURATION_MS, SootherPolicy.clampDurationMs(SootherPolicy.MIN_DURATION_MS))
        assertEquals(SootherPolicy.MAX_DURATION_MS, SootherPolicy.clampDurationMs(SootherPolicy.MAX_DURATION_MS))
    }
}
