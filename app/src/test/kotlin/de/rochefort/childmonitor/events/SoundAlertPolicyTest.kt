package de.rochefort.childmonitor.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #286: only cry/cough above the confidence floor alert — ambient "noise" is suppressed. */
class SoundAlertPolicyTest {

    @Test fun cryAboveFloorAlerts() {
        assertTrue(SoundAlertPolicy.isAlertWorthy("cry", 0.9))
        assertTrue(SoundAlertPolicy.isAlertWorthy("cry", SoundAlertPolicy.MIN_CONFIDENCE))
    }

    @Test fun coughAboveFloorAlerts() {
        assertTrue(SoundAlertPolicy.isAlertWorthy("cough", 0.7))
    }

    @Test fun lowConfidenceDoesNotAlert() {
        assertFalse(SoundAlertPolicy.isAlertWorthy("cry", 0.3))
    }

    @Test fun genericNoiseNeverAlerts() {
        assertFalse(SoundAlertPolicy.isAlertWorthy("noise", 0.99))
    }
}
