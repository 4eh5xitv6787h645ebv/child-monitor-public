/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package de.rochefort.childmonitor

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure decision logic for [ChildControlsActivity]'s status→slider synchronisation, factored out so
 * the subtle stale-stomp guard (Codex) can be unit-tested without a live Activity / Context.
 */
object ChildControlsLogic {

    /** Sentinel for "the child didn't report this value", so the slider is left untouched. */
    const val NO_VALUE = -1

    /**
     * Convert a child-reported 0..1 [fraction] (brightness/volume) to a 0..100 slider percent,
     * or [NO_VALUE] when the fraction is absent/out of range/non-finite.
     */
    fun sliderPercent(fraction: Double): Int {
        if (!fraction.isFinite() || fraction < 0.0 || fraction > 1.0) return NO_VALUE
        return (fraction * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Apply a status-seeded slider value only when it's safe to: the user isn't currently
     * [dragging], no slider touch has happened since this status read began
     * ([genAtFetch] == [genNow]), and the value is a real 0..100 percent. This is what stops a slow
     * status read that started before a drag from yanking the slider back after release.
     */
    fun shouldSeed(dragging: Boolean, genAtFetch: Int, genNow: Int, percent: Int): Boolean =
        !dragging && genAtFetch == genNow && percent in 0..100

    /** Whole minutes remaining for a sleep-timer, rounded UP so 1ms..60s reads "1m", never "0m". */
    fun minutesRemaining(remainingMs: Long): Long =
        if (remainingMs <= 0L) 0L else (remainingMs + 59_999L) / 60_000L

    const val MIN_DURATION_MINUTES = 1
    const val MAX_DURATION_MINUTES = 24 * 60

    /** Validate the parent's custom minute entry without silently changing what they typed. */
    fun durationFromMinutes(minutes: Int?): Long? =
        minutes?.takeIf { it in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES }?.times(60_000L)

    /**
     * Duration until the next occurrence of [hour]:[minute] in [timeZone]. A time equal to or
     * earlier than now means tomorrow. Calendar intentionally handles DST gaps/fallbacks for us.
     */
    fun durationUntilClockTime(
        nowMs: Long,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val target = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_MONTH, 1)
        }
        return BedtimeTimerPolicy.clampDurationMs(target.timeInMillis - nowMs)
    }
}
