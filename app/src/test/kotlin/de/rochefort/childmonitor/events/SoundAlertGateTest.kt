package de.rochefort.childmonitor.events

import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #291: the combined alert gate — per-trigger toggle AND alert-worthiness AND quiet-hours
 * escalation must all pass before a sound event raises an alert.
 */
class SoundAlertGateTest {

    private fun cal(hour: Int, minute: Int = 0): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }

    /** Quiet hours disabled so it never interferes — isolates the type/confidence checks. */
    private val noQuiet = QuietHoursRule(enabled = false)

    @Test fun `enabled high-confidence cry alerts`() {
        assertTrue(
            SoundAlertGate.shouldAlert(
                type = "cry", confidence = 0.9,
                cryEnabled = true, coughEnabled = true,
                quietHours = noQuiet, calendar = cal(12)
            )
        )
    }

    @Test fun `enabled cough alerts when cry disabled`() {
        assertTrue(
            SoundAlertGate.shouldAlert(
                type = "cough", confidence = 0.9,
                cryEnabled = false, coughEnabled = true,
                quietHours = noQuiet, calendar = cal(12)
            )
        )
    }

    @Test fun `disabled cry trigger never alerts`() {
        assertFalse(
            SoundAlertGate.shouldAlert(
                type = "cry", confidence = 0.99,
                cryEnabled = false, coughEnabled = true,
                quietHours = noQuiet, calendar = cal(12)
            )
        )
    }

    @Test fun `disabled cough trigger never alerts`() {
        assertFalse(
            SoundAlertGate.shouldAlert(
                type = "cough", confidence = 0.99,
                cryEnabled = true, coughEnabled = false,
                quietHours = noQuiet, calendar = cal(12)
            )
        )
    }

    @Test fun `confidence below policy floor never alerts`() {
        assertFalse(
            SoundAlertGate.shouldAlert(
                type = "cry", confidence = 0.30,
                cryEnabled = true, coughEnabled = true,
                quietHours = noQuiet, calendar = cal(12)
            )
        )
    }

    @Test fun `unknown trigger type never alerts`() {
        assertFalse(
            SoundAlertGate.shouldAlert(
                type = "noise", confidence = 0.99,
                cryEnabled = true, coughEnabled = true,
                quietHours = noQuiet, calendar = cal(12)
            )
        )
    }

    @Test fun `quiet hours suppress a sub-floor cry`() {
        // 0.70 is above the policy floor (0.55) but below the escalation floor (0.85).
        val quiet = QuietHoursRule(startHour = 22, endHour = 6, escalationFloor = 0.85)
        assertFalse(
            SoundAlertGate.shouldAlert(
                type = "cry", confidence = 0.70,
                cryEnabled = true, coughEnabled = true,
                quietHours = quiet, calendar = cal(23)
            )
        )
    }

    @Test fun `quiet hours still escalate an at-floor cry`() {
        val quiet = QuietHoursRule(startHour = 22, endHour = 6, escalationFloor = 0.85)
        assertTrue(
            SoundAlertGate.shouldAlert(
                type = "cry", confidence = 0.90,
                cryEnabled = true, coughEnabled = true,
                quietHours = quiet, calendar = cal(23)
            )
        )
    }

    @Test fun `outside quiet hours a sub-floor cry alerts`() {
        val quiet = QuietHoursRule(startHour = 22, endHour = 6, escalationFloor = 0.85)
        assertTrue(
            SoundAlertGate.shouldAlert(
                type = "cry", confidence = 0.70,
                cryEnabled = true, coughEnabled = true,
                quietHours = quiet, calendar = cal(10)
            )
        )
    }

    @Test fun `parseHhMm accepts valid times`() {
        assertEquals(22 to 30, SoundAlertGate.parseHhMm("22:30"))
        assertEquals(6 to 5, SoundAlertGate.parseHhMm("06:05"))
        assertEquals(0 to 0, SoundAlertGate.parseHhMm("0:0"))
        assertEquals(23 to 59, SoundAlertGate.parseHhMm(" 23:59 "))
    }

    @Test fun `parseHhMm rejects malformed input`() {
        assertNull(SoundAlertGate.parseHhMm(null))
        assertNull(SoundAlertGate.parseHhMm(""))
        assertNull(SoundAlertGate.parseHhMm("22"))
        assertNull(SoundAlertGate.parseHhMm("22:30:00"))
        assertNull(SoundAlertGate.parseHhMm("ab:cd"))
    }

    @Test fun `parseHhMm rejects out-of-range values`() {
        assertNull(SoundAlertGate.parseHhMm("24:00"))
        assertNull(SoundAlertGate.parseHhMm("22:60"))
        assertNull(SoundAlertGate.parseHhMm("-1:00"))
    }

    @Test fun `AlertConfig overload matches the field-wise overload`() {
        val quiet = QuietHoursRule(startHour = 22, endHour = 6, escalationFloor = 0.85)
        val config = AlertConfig(cryEnabled = true, coughEnabled = false, quietHours = quiet)
        // cry honored, cough disabled, quiet-hours escalation applied — all via the bundled config.
        assertTrue(SoundAlertGate.shouldAlert("cry", 0.90, config, cal(23)))
        assertFalse(SoundAlertGate.shouldAlert("cry", 0.70, config, cal(23)))
        assertFalse(SoundAlertGate.shouldAlert("cough", 0.90, config, cal(12)))
    }

    @Test fun `default AlertConfig preserves alert-on-cry-and-cough behavior`() {
        val config = AlertConfig()
        assertTrue(SoundAlertGate.shouldAlert("cry", 0.90, config, cal(3)))
        assertTrue(SoundAlertGate.shouldAlert("cough", 0.90, config, cal(3)))
        assertFalse(SoundAlertGate.shouldAlert("noise", 0.99, config, cal(3)))
    }

    @Test fun `motion toggle and quiet hours gate motion alerts`() {
        val enabled = AlertConfig(motionEnabled = true)
        val disabled = AlertConfig(motionEnabled = false)
        assertTrue(SoundAlertGate.shouldAlert("motion", 0.30, enabled, cal(12)))
        assertFalse(SoundAlertGate.shouldAlert("motion", 0.30, disabled, cal(12)))
        assertFalse(SoundAlertGate.shouldAlert("motion", 0.10, enabled, cal(12)))
        val quiet = enabled.copy(quietHours = QuietHoursRule(
            startHour = 22, endHour = 6, escalationFloor = 0.85,
        ))
        assertFalse(SoundAlertGate.shouldAlert("motion", 0.30, quiet, cal(23)))
        assertTrue(SoundAlertGate.shouldAlert("motion", 0.90, quiet, cal(23)))
    }
}
