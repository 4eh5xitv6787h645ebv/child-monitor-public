package de.rochefort.childmonitor.tracker

import java.util.Calendar

/**
 * Shared time helpers. One source of truth for the "today" boundary and the
 * day-in-milliseconds constant so the dashboard, diaper entry, and any future
 * summary view cannot drift apart (CM-TRK-CR4-P1-5).
 */
object TrackerTime {

    const val DAY_MS: Long = 24L * 3600L * 1000L

    /** Returns the wall-clock epoch ms for local midnight of the day containing [nowMs]. */
    fun startOfLocalDayMs(nowMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
