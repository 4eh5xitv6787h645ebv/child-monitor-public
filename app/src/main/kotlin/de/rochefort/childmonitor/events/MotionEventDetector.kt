package de.rochefort.childmonitor.events

import kotlin.math.abs

/**
 * Lightweight child-side motion detector operating directly on the Y plane from CameraX.
 *
 * A small luma grid keeps CPU cost bounded. Subtracting the mean whole-frame shift rejects common
 * camera auto-exposure / IR transitions, while consecutive changed samples reject one-frame noise.
 */
class MotionEventDetector(
    private val gridX: Int = 12,
    private val gridY: Int = 8,
    private val bucketDelta: Int = 18,
    private val triggerPercent: Int = 18,
    private val requiredSamples: Int = 3,
    private val sampleIntervalMs: Long = 500L,
    private val cooldownMs: Long = 30_000L,
) {
    data class Event(
        val startMs: Long,
        val durationMs: Long,
        val confidence: Double,
        val changedPercent: Int,
    )

    private var previous: IntArray? = null
    private var lastSampleMs = Long.MIN_VALUE
    private var consecutive = 0
    private var runStartMs = 0L
    private var lastEventMs = Long.MIN_VALUE

    fun reset() {
        previous = null
        lastSampleMs = Long.MIN_VALUE
        consecutive = 0
        runStartMs = 0L
        lastEventMs = Long.MIN_VALUE
    }

    fun process(yPlane: ByteArray, width: Int, height: Int, timestampMs: Long): Event? {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(yPlane.size >= width * height) { "Y plane is smaller than frame" }
        if (lastSampleMs != Long.MIN_VALUE && timestampMs - lastSampleMs < sampleIntervalMs) return null
        lastSampleMs = timestampMs

        val current = signature(yPlane, width, height)
        val prior = previous
        previous = current
        if (prior == null) return null

        // Median is robust to a moving subject occupying a minority of the frame. A mean would let
        // that local movement drag the whole-frame baseline far enough to mark unchanged cells as
        // changed (inflating a 25% subject into a 100% event).
        val deltas = IntArray(current.size) { current[it] - prior[it] }.apply { sort() }
        val middle = deltas.size / 2
        val globalShift = if (deltas.size % 2 == 0) {
            (deltas[middle - 1] + deltas[middle]) / 2.0
        } else {
            deltas[middle].toDouble()
        }
        var changed = 0
        for (i in current.indices) {
            val localDelta = (current[i] - prior[i]) - globalShift
            if (abs(localDelta) >= bucketDelta) changed++
        }
        val changedPercent = (changed * 100) / current.size
        if (changedPercent < triggerPercent) {
            consecutive = 0
            return null
        }

        if (consecutive == 0) runStartMs = timestampMs
        consecutive++
        if (consecutive < requiredSamples) return null
        if (lastEventMs != Long.MIN_VALUE && timestampMs - lastEventMs < cooldownMs) return null

        lastEventMs = timestampMs
        consecutive = 0
        return Event(
            startMs = runStartMs,
            durationMs = (timestampMs - runStartMs + sampleIntervalMs).coerceAtLeast(sampleIntervalMs),
            confidence = (changedPercent / 100.0).coerceIn(0.0, 1.0),
            changedPercent = changedPercent,
        )
    }

    private fun signature(yPlane: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(gridX * gridY)
        val cellW = (width / gridX).coerceAtLeast(1)
        val cellH = (height / gridY).coerceAtLeast(1)
        var i = 0
        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val x = (gx * cellW + cellW / 2).coerceAtMost(width - 1)
                val y = (gy * cellH + cellH / 2).coerceAtMost(height - 1)
                out[i++] = yPlane[y * width + x].toInt() and 0xff
            }
        }
        return out
    }
}
