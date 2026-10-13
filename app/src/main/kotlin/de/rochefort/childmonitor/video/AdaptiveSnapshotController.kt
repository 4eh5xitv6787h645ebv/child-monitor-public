package de.rochefort.childmonitor.video

import de.rochefort.childmonitor.reliability.MonotonicClock

/**
 * Adaptive snapshot rate controller (activity/thermal/battery aware).
 * Implements CM-VID-008: Adaptive snapshot rate controller.
 *
 * Decides the next snapshot interval. Faster when motion is detected, slower
 * under thermal pressure or battery saver. Caller invokes nextIntervalMs() on
 * every frame decision.
 */
class AdaptiveSnapshotController(
    private val baseIntervalMs: Long = 1_000L,
    private val minIntervalMs: Long = 500L,
    private val maxIntervalMs: Long = 10_000L
) {

    data class Inputs(
        val motionScore: Double = 0.0,
        val thermal: ThermalProfileManager.Profile = ThermalProfileManager.Profile.FULL,
        val batterySaver: Boolean = false,
        val chargingPlugged: Boolean = true,
        val queueDepthMs: Int = 0
    )

    private var lastTickMs: Long = 0L

    fun nextIntervalMs(inputs: Inputs): Long {
        val now = MonotonicClock.nowMs()
        lastTickMs = now

        val thermalFactor = when (inputs.thermal) {
            ThermalProfileManager.Profile.FULL -> 1.0
            ThermalProfileManager.Profile.HALF -> 2.0
            ThermalProfileManager.Profile.QUARTER -> 4.0
            ThermalProfileManager.Profile.PAUSED -> 0.0
        }
        if (thermalFactor == 0.0) return Long.MAX_VALUE

        val batteryFactor = if (inputs.batterySaver && !inputs.chargingPlugged) 2.5 else 1.0
        val queueFactor = when {
            inputs.queueDepthMs < 100 -> 1.0
            inputs.queueDepthMs < 400 -> 1.6
            else -> 2.4
        }
        val motionSpeedUp = when {
            inputs.motionScore > 0.6 -> 0.25
            inputs.motionScore > 0.3 -> 0.5
            inputs.motionScore > 0.1 -> 0.8
            else -> 1.0
        }

        val total = (baseIntervalMs * thermalFactor * batteryFactor * queueFactor * motionSpeedUp).toLong()
        return total.coerceIn(minIntervalMs, maxIntervalMs)
    }
}
