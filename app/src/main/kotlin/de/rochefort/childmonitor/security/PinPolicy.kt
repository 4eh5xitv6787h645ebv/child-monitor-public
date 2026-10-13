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
package de.rochefort.childmonitor.security

/**
 * Pure rules for the optional in-app app-lock PIN (#375): format validation + a strictly
 * time-bounded, escalating backoff after wrong attempts.
 *
 * FAIL-SAFE by construction: the backoff only ever *delays* the next PIN attempt (capped at
 * [MAX_BACKOFF_MS]); it is NEVER permanent and never disables unlock. The device-credential prompt
 * (always available, since the PIN can only be set when the device is secure) bypasses this backoff
 * entirely, so a forgotten PIN can never lock the user out — it's a speed bump on guessing, not a gate.
 *
 * Pure functions of (attempt count, last-failure time, nowMs) so they unit-test without a clock.
 */
object PinPolicy {

    const val MIN_LEN = 4
    const val MAX_LEN = 8
    const val MAX_BACKOFF_MS = 60_000L

    /** Escalating delay (ms) required *after* N consecutive failures, indexed by failure count.
     *  Failures 1–3 are free (fat-finger tolerance); then 5s/15s/30s/60s, capped at the last. */
    private val BACKOFF_STEPS_MS = longArrayOf(0L, 0L, 0L, 0L, 5_000L, 15_000L, 30_000L, MAX_BACKOFF_MS)

    /** A PIN must be [MIN_LEN]–[MAX_LEN] digits. */
    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_LEN..MAX_LEN && pin.all { it in '0'..'9' }

    /** The backoff delay imposed after [failedAttempts] consecutive wrong PINs (0 = none). */
    fun backoffMs(failedAttempts: Int): Long {
        if (failedAttempts <= 0) return 0L
        return BACKOFF_STEPS_MS[failedAttempts.coerceAtMost(BACKOFF_STEPS_MS.size - 1)]
    }

    /**
     * Milliseconds the user must still wait before the next PIN attempt, given the consecutive
     * [failedAttempts] and when the last one happened ([lastFailedAtMs]). 0 means "may try now".
     * Negative elapsed (clock moved back) is treated conservatively as the full (bounded) delay.
     */
    fun remainingBackoffMs(failedAttempts: Int, lastFailedAtMs: Long, nowMs: Long): Long {
        val delay = backoffMs(failedAttempts)
        if (delay <= 0L) return 0L
        val elapsed = nowMs - lastFailedAtMs
        if (elapsed < 0L) return delay
        return (delay - elapsed).coerceAtLeast(0L)
    }

    /** Whether a PIN attempt is allowed right now (backoff elapsed). */
    fun canAttempt(failedAttempts: Int, lastFailedAtMs: Long, nowMs: Long): Boolean =
        remainingBackoffMs(failedAttempts, lastFailedAtMs, nowMs) <= 0L
}
