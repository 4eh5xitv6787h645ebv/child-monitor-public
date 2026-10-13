package de.rochefort.childmonitor.network

import de.rochefort.childmonitor.reliability.MonotonicClock
import kotlin.math.max
import kotlin.math.min

/**
 * Connection health scorer and prioritized failover endpoint ring.
 * Implements CM-NET-005: Connection health scoring + prioritized failover endpoint ring.
 *
 * Each endpoint maintains a rolling score in [0, 100]. Successful writes
 * increase the score; errors and long write latencies decrease it. The ring
 * iterator returns candidates in descending score order, round-robining
 * between ties so we don't hammer a single endpoint.
 */
class ConnectionHealthScorer {

    data class Endpoint(
        val key: String,
        var score: Double = 80.0,
        var lastUsedMs: Long = 0L,
        var consecutiveFailures: Int = 0,
        var totalWrites: Long = 0L,
        var totalFailures: Long = 0L
    )

    private val endpoints = linkedMapOf<String, Endpoint>()

    fun observeSuccess(key: String, latencyMs: Long = 0) {
        val ep = endpoints.getOrPut(key) { Endpoint(key) }
        ep.totalWrites++
        ep.consecutiveFailures = 0
        ep.lastUsedMs = MonotonicClock.nowMs()
        val latencyBoost = when {
            latencyMs <= 50 -> 3.0
            latencyMs <= 150 -> 2.0
            latencyMs <= 300 -> 1.0
            else -> 0.0
        }
        ep.score = min(100.0, ep.score + latencyBoost + 0.5)
    }

    fun observeFailure(key: String, weight: Double = 15.0) {
        val ep = endpoints.getOrPut(key) { Endpoint(key) }
        ep.totalFailures++
        ep.consecutiveFailures++
        val penalty = weight + min(15.0, ep.consecutiveFailures.toDouble())
        ep.score = max(0.0, ep.score - penalty)
    }

    fun register(key: String) {
        endpoints.getOrPut(key) { Endpoint(key) }
    }

    fun remove(key: String) {
        endpoints.remove(key)
    }

    fun prioritized(): List<Endpoint> {
        return endpoints.values.sortedWith(
            compareByDescending<Endpoint> { it.score }
                .thenBy { it.lastUsedMs }
        )
    }

    fun recoveryCandidate(): Endpoint? {
        return endpoints.values.filter { it.score > 40 }.maxByOrNull { it.score }
    }

    fun snapshot(): Map<String, Endpoint> = endpoints.toMap()
}
