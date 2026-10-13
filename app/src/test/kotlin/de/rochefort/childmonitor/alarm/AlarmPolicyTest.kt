package de.rochefort.childmonitor.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/** #290: pure-logic tests for the disconnect alert mode decision. */
class AlarmPolicyTest {

    @Test fun defaultIsOneShotBeep() {
        assertEquals(AlertMode.ONE_SHOT, AlarmPolicy.mode(persistentEnabled = false, muted = false))
    }

    @Test fun mutedSilencesOneShot() {
        assertEquals(AlertMode.SILENT, AlarmPolicy.mode(persistentEnabled = false, muted = true))
    }

    @Test fun persistentWhenEnabled() {
        assertEquals(AlertMode.PERSISTENT, AlarmPolicy.mode(persistentEnabled = true, muted = false))
    }

    @Test fun persistentOverridesMute() {
        // Explicit opt-in beats the one-shot mute (incl. the reconnect loop's force-mute),
        // so a deliberately-set persistent alarm keeps sounding through reconnect attempts.
        assertEquals(AlertMode.PERSISTENT, AlarmPolicy.mode(persistentEnabled = true, muted = true))
    }

    // #357: per-effect alert delivery (sound / vibration / screen-flash / torch) is modelled by
    // AlertEffects + tested in AlertEffectsPolicyTest. AlarmPolicy now governs only the SOUND
    // channel's mode (above); vibration/flash/torch are fired independently by the caller.
}
