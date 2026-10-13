package de.rochefort.childmonitor.events

import java.util.Calendar

/**
 * Quiet-hours suppression and escalation rules.
 * Implements CM-EVT-004: Quiet-hours suppression and escalation rules.
 *
 * Quiet hours window is inclusive of the start, exclusive of the end. Window
 * may wrap across midnight. During quiet hours, events below [escalationFloor]
 * are suppressed; events at or above it are always forwarded to the parent.
 */
data class QuietHoursRule(
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 6,
    val endMinute: Int = 0,
    val escalationFloor: Double = 0.85,
    val enabled: Boolean = true
) {

    fun isActiveNow(calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return false
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        return if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    fun shouldEscalate(confidence: Double, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return true
        if (!isActiveNow(calendar)) return true
        return confidence >= escalationFloor
    }
}
