package de.rochefort.childmonitor.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #301 (parent side): edge-triggered child low-battery alert. */
class ChildBatteryAlertStateTest {

    @Test fun firesOnceWhenEnteringLow() {
        val s = ChildBatteryAlertState(threshold = 15)
        assertTrue(s.onReading(10, charging = false))   // entered low → fire
        assertFalse(s.onReading(9, charging = false))   // still low → no repeat
        assertFalse(s.onReading(8, charging = false))
    }

    @Test fun reArmsAfterRecovery() {
        val s = ChildBatteryAlertState(threshold = 15)
        assertTrue(s.onReading(10, charging = false))
        assertFalse(s.onReading(50, charging = false))  // recovered → re-arm (no alert)
        assertTrue(s.onReading(12, charging = false))   // dropped low again → fire
    }

    @Test fun chargingClearsAndSuppresses() {
        val s = ChildBatteryAlertState(threshold = 15)
        assertFalse(s.onReading(10, charging = true))   // low but charging → no alert
        assertTrue(s.onReading(10, charging = false))   // unplugged while low → fire
        assertFalse(s.onReading(10, charging = true))   // plugged back → re-arm, no alert
        assertTrue(s.onReading(10, charging = false))   // unplugged again → fire
    }

    @Test fun unknownLevelNeverAlerts() {
        val s = ChildBatteryAlertState(threshold = 15)
        assertFalse(s.onReading(-1, charging = false))
    }

    @Test fun aboveThresholdNeverAlerts() {
        val s = ChildBatteryAlertState(threshold = 15)
        assertFalse(s.onReading(16, charging = false))
        assertFalse(s.onReading(80, charging = false))
    }

    @Test fun resetReArms() {
        val s = ChildBatteryAlertState(threshold = 15)
        assertTrue(s.onReading(10, charging = false))
        s.reset()
        assertTrue(s.onReading(10, charging = false))   // after reset, fires again
    }
}
