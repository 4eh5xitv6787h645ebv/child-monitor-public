package de.rochefort.childmonitor.reliability

import java.util.concurrent.ConcurrentHashMap

/**
 * Detects sessions that look connected but are not exchanging data.
 * Implements CM-REL-009: Zombie-session detector with automatic lease cleanup.
 *
 * A session registers with `track(key, ts)` on each successful read/write.
 * `sweep()` returns keys that have exceeded [staleMs] without activity so the
 * caller can close the underlying socket and free the lease.
 */
class ZombieSessionDetector(
    private val staleMs: Long = 45_000L,
    private val nowMs: () -> Long = MonotonicClock::nowMs
) {

    private val lastActivity = ConcurrentHashMap<String, Long>()

    fun track(key: String) {
        lastActivity[key] = nowMs()
    }

    fun drop(key: String) {
        lastActivity.remove(key)
    }

    fun sweep(): List<String> {
        val now = nowMs()
        val zombies = lastActivity.entries
            .asSequence()
            .filter { now - it.value > staleMs }
            .map { it.key }
            .toList()
        for (k in zombies) lastActivity.remove(k)
        return zombies
    }

    fun size(): Int = lastActivity.size
}
