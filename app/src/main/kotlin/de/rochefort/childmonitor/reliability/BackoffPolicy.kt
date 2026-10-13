package de.rochefort.childmonitor.reliability

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with jitter and a restart loop guard.
 * Implements CM-REL-007: Self-healing restart policy with exponential backoff and loop guard.
 *
 * - delay() returns the next backoff delay (ms).
 * - onSuccess() resets the backoff and marks a stable run.
 * - inRestartLoop() detects when too many restarts happened in a short window.
 */
class BackoffPolicy(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
    private val jitterRatio: Double = 0.20,
    private val loopWindowMs: Long = 60_000L,
    private val loopThreshold: Int = 10
) {

    private var attempts = 0
    private val restartTimestamps = ArrayDeque<Long>()

    @Synchronized
    fun delay(): Long {
        attempts++
        restartTimestamps.addLast(MonotonicClock.nowMs())
        while (restartTimestamps.isNotEmpty() &&
            MonotonicClock.nowMs() - restartTimestamps.first() > loopWindowMs) {
            restartTimestamps.removeFirst()
        }
        val shift = min(attempts - 1, 10)
        val base = min(maxMs, initialMs shl shift)
        val jitter = (base * jitterRatio).toLong()
        val noise = if (jitter == 0L) 0L else Random.nextLong(-jitter, jitter + 1)
        return (base + noise).coerceAtLeast(initialMs)
    }

    @Synchronized
    fun onSuccess() {
        attempts = 0
        restartTimestamps.clear()
    }

    @Synchronized
    fun inRestartLoop(): Boolean = restartTimestamps.size > loopThreshold

    @Synchronized
    fun attempts(): Int = attempts
}
