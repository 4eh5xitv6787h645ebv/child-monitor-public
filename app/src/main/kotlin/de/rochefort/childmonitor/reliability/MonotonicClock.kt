package de.rochefort.childmonitor.reliability

import android.os.SystemClock
import kotlin.math.abs

/**
 * Monotonic time source with wall-clock fallback detection.
 * Implements CM-REL-010: Monotonic time-source fallback when wall clock jumps.
 *
 * Use `nowMs()` for any duration calculation. Use `wallMs()` only for timestamps
 * that must be human-readable. Use `detectJump(prevWallMs, prevMonoMs)` to test
 * whether the wall clock jumped between two samples.
 */
object MonotonicClock {

    /** Milliseconds since device boot, not affected by wall-clock changes. */
    fun nowMs(): Long = SystemClock.elapsedRealtime()

    /** Milliseconds since device boot in nanoseconds resolution. */
    fun nowNanos(): Long = System.nanoTime()

    /** Wall clock - only for display/storage, NOT for duration math. */
    fun wallMs(): Long = System.currentTimeMillis()

    /**
     * Returns true if wall clock drifted from monotonic progression by more than [toleranceMs].
     * Callers snapshot both clocks at t1 and t2, then call:
     *   detectJump(wallDelta = t2wall - t1wall, monoDelta = t2mono - t1mono)
     */
    fun detectJump(wallDelta: Long, monoDelta: Long, toleranceMs: Long = 5_000L): Boolean {
        return abs(wallDelta - monoDelta) > toleranceMs
    }

    /**
     * Build a durable sample pair for embedding in event records.
     * Use monoSinceBootMs + wall epoch millis together so consumers can correct
     * for clock jumps without losing the original stamp.
     */
    data class Sample(
        val wallEpochMs: Long,
        val monoSinceBootMs: Long
    )

    fun sample(): Sample = Sample(wallMs(), nowMs())
}
