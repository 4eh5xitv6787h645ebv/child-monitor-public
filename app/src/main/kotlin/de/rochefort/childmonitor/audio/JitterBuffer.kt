package de.rochefort.childmonitor.audio

import de.rochefort.childmonitor.reliability.MonotonicClock
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive jitter buffer with drift-safe playout control.
 * Implements CM-AUD-006: Implement adaptive jitter buffer + drift-safe playout control.
 *
 * Packets are placed in arrival order; the buffer adjusts target depth based on
 * observed inter-arrival jitter. Holes are concealed by repeating the previous
 * sample (hook for CM-AUD-005 PLC integration).
 */
class JitterBuffer(
    private val initialTargetMs: Int = 80,
    private val minTargetMs: Int = 40,
    private val maxTargetMs: Int = 320,
    private val frameDurationMs: Int = 20
) {

    data class Packet(val seq: Long, val captureTsMs: Long, val samples: ShortArray) :
        Comparable<Packet> {
        override fun compareTo(other: Packet): Int = this.seq.compareTo(other.seq)
        override fun equals(other: Any?): Boolean = other is Packet && other.seq == seq
        override fun hashCode(): Int = seq.hashCode()
    }

    private val queue = PriorityBlockingQueue<Packet>()
    private var targetMs: Int = initialTargetMs
    private var lastExpectedArrival: Long = 0L
    private var lastArrival: Long = 0L
    private var jitterEstimateMs: Double = 0.0
    private val dropped = AtomicInteger(0)
    private val lateArrivals = AtomicInteger(0)
    private var lastDeliveredSeq: Long = -1L
    private var lastFrame: ShortArray? = null

    fun enqueue(packet: Packet) {
        val now = MonotonicClock.nowMs()
        if (lastArrival != 0L) {
            val inter = now - lastArrival
            val error = kotlin.math.abs(inter - frameDurationMs)
            jitterEstimateMs = jitterEstimateMs + (error - jitterEstimateMs) / 16.0
            adjustTarget()
        }
        lastArrival = now
        if (packet.seq <= lastDeliveredSeq) {
            lateArrivals.incrementAndGet()
            return
        }
        queue.offer(packet)
        trimBacklog()
    }

    private fun adjustTarget() {
        val candidate = (jitterEstimateMs * 4).toInt().coerceIn(minTargetMs, maxTargetMs)
        val blended = ((targetMs * 7 + candidate) / 8).coerceIn(minTargetMs, maxTargetMs)
        targetMs = blended
    }

    private fun trimBacklog() {
        val maxFrames = max(1, targetMs * 4 / frameDurationMs)
        while (queue.size > maxFrames) {
            queue.poll()?.let { dropped.incrementAndGet() }
        }
    }

    /**
     * Pop the next playout frame. Returns null if buffer is below target depth at start.
     * When a gap is detected, returns the previous frame as a simple PLC fallback.
     */
    fun pop(): ShortArray? {
        val first = queue.peek() ?: return null
        val depthMs = queue.size * frameDurationMs
        if (lastDeliveredSeq < 0 && depthMs < targetMs) return null
        queue.poll()
        if (lastDeliveredSeq >= 0 && first.seq > lastDeliveredSeq + 1) {
            val missing = (first.seq - lastDeliveredSeq - 1)
            for (i in 0 until min(missing, 3L)) {
                lateArrivals.incrementAndGet()
            }
            queue.offer(first)
            lastDeliveredSeq++
            return lastFrame?.copyOf() ?: ShortArray(first.samples.size)
        }
        lastDeliveredSeq = first.seq
        lastFrame = first.samples
        return first.samples
    }

    fun metrics(): Metrics = Metrics(
        depthFrames = queue.size,
        targetMs = targetMs,
        jitterMs = jitterEstimateMs,
        dropped = dropped.get(),
        late = lateArrivals.get()
    )

    data class Metrics(
        val depthFrames: Int,
        val targetMs: Int,
        val jitterMs: Double,
        val dropped: Int,
        val late: Int
    )
}
