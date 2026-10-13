package de.rochefort.childmonitor

/** Shared bounds and decisions for the independent night-light and soother timers (#490). */
object BedtimeTimerPolicy {
    const val MIN_DURATION_MS = 60_000L

    // A clock-time selection can span 25 hours across a daylight-saving fallback. Keep one extra
    // hour of headroom while still rejecting absurd or overflow-prone remote requests.
    const val MAX_DURATION_MS = 26L * 60L * 60L * 1000L

    /** Non-positive clears a timer; positive values are constrained to a useful one-night window. */
    fun clampDurationMs(requested: Long?): Long? = when {
        requested == null || requested <= 0L -> null
        else -> requested.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /** Milliseconds left for status JSON without ever reporting a negative or overflowing value. */
    fun remainingMs(deadlineMs: Long, nowMs: Long): Long =
        if (deadlineMs <= 0L || deadlineMs <= nowMs) 0L else deadlineMs - nowMs

    /** The screen may lock only after both independently controlled comfort features are off. */
    fun shouldLockScreen(nightLightOn: Boolean, sootherPlaying: Boolean): Boolean =
        !nightLightOn && !sootherPlaying
}
