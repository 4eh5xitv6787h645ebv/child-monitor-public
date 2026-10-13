package de.rochefort.childmonitor.test

import java.util.Random

/**
 * Deterministic network emulator harness (loss / jitter / reorder).
 * Implements CM-TST-007: Deterministic network emulator harness (loss/jitter/reorder).
 *
 * Wraps a send/recv callback. Given a seed, the emulator reproduces the same
 * loss/jitter/reorder sequence, so debugging networking code becomes a
 * regression test instead of a flaky one-off. Used both by soak tests and the
 * `NetworkChaos` helper for on-device experimentation.
 */
class NetworkEmulator(
    private val profile: Profile = Profile.WIFI,
    seed: Long = 0x5CA1AB1EL
) {

    enum class Profile(val lossPct: Double, val jitterMs: IntRange, val reorderPct: Double) {
        WIFI(lossPct = 0.2, jitterMs = 0..20, reorderPct = 0.1),
        VPN(lossPct = 0.8, jitterMs = 20..120, reorderPct = 0.5),
        LTE(lossPct = 1.5, jitterMs = 40..200, reorderPct = 1.0),
        BAD(lossPct = 7.0, jitterMs = 100..400, reorderPct = 3.0),
        PERFECT(lossPct = 0.0, jitterMs = 0..0, reorderPct = 0.0)
    }

    private val rng = Random(seed)
    private val delayedQueue = ArrayDeque<Delayed>()
    private data class Delayed(val tsMs: Long, val payload: ByteArray)

    /**
     * `send(bytes)` returns the set of delivery events that should actually
     * fire at time `nowMs`. It models loss, jitter, and occasional reorder.
     */
    fun send(bytes: ByteArray, nowMs: Long): List<Delivery> {
        if (rng.nextDouble() * 100.0 < profile.lossPct) {
            return listOf(Delivery.Lost)
        }
        val jitter = profile.jitterMs.randomOrZero(rng)
        val deliverAt = nowMs + jitter
        delayedQueue.addLast(Delayed(deliverAt, bytes))
        if (rng.nextDouble() * 100.0 < profile.reorderPct && delayedQueue.size >= 2) {
            val swapIdx = delayedQueue.size - 2
            val a = delayedQueue.elementAt(swapIdx)
            val b = delayedQueue.last()
            delayedQueue.removeAt(swapIdx)
            delayedQueue.removeLast()
            delayedQueue.add(swapIdx, b)
            delayedQueue.addLast(a)
        }
        return flushReady(nowMs)
    }

    fun flushReady(nowMs: Long): List<Delivery> {
        val out = mutableListOf<Delivery>()
        while (delayedQueue.isNotEmpty() && delayedQueue.first().tsMs <= nowMs) {
            out.add(Delivery.Received(delayedQueue.removeFirst().payload))
        }
        return out
    }

    private fun IntRange.randomOrZero(rng: Random): Int {
        val lo = this.first
        val hi = this.last
        if (hi <= lo) return lo
        return lo + rng.nextInt(hi - lo + 1)
    }

    sealed interface Delivery {
        data class Received(val bytes: ByteArray) : Delivery {
            override fun equals(other: Any?): Boolean = other is Received && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }
        object Lost : Delivery
    }
}
