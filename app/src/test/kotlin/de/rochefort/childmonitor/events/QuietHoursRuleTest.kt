package de.rochefort.childmonitor.events

import org.junit.Test
import java.util.Calendar
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursRuleTest {

    private fun cal(hour: Int, minute: Int = 0): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }

    @Test fun `active during wrap-around window at midnight`() {
        val rule = QuietHoursRule(startHour = 22, endHour = 6)
        assertTrue(rule.isActiveNow(cal(23)))
        assertTrue(rule.isActiveNow(cal(2)))
        assertFalse(rule.isActiveNow(cal(10)))
    }

    @Test fun `non-wrap window respects start and end`() {
        val rule = QuietHoursRule(startHour = 13, endHour = 15)
        assertTrue(rule.isActiveNow(cal(14)))
        assertFalse(rule.isActiveNow(cal(12)))
        assertFalse(rule.isActiveNow(cal(15)))
    }

    @Test fun `low confidence events are suppressed during quiet hours`() {
        val cal = cal(23)
        val rule = QuietHoursRule(startHour = 22, endHour = 6, escalationFloor = 0.85)
        assertTrue(rule.isActiveNow(cal))
        assertFalse(rule.shouldEscalate(0.70, cal))
        assertTrue(rule.shouldEscalate(0.9, cal))
    }

    @Test fun `disabled rule always escalates`() {
        val rule = QuietHoursRule(enabled = false)
        assertTrue(rule.shouldEscalate(0.10))
    }
}
