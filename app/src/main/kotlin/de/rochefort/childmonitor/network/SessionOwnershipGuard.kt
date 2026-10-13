package de.rochefort.childmonitor.network

import de.rochefort.childmonitor.reliability.MonotonicClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Session ownership guard to prevent duplicate parent streams.
 * Implements CM-NET-007: Session ownership guard to prevent duplicate parent streams.
 *
 * Each parent device may hold at most one active audio stream to the child.
 * If a second connection arrives for the same deviceId, the prior one is
 * evicted cleanly. A short grace window prevents rapid flapping during
 * network handoffs.
 */
class SessionOwnershipGuard(
    private val graceMs: Long = 2_000L,
    // [#242] Use a monotonic clock for the grace-window duration math. With wall-clock time,
    // a backward step (NTP correction / user clock change) made `now - acquiredMs` negative,
    // which is < graceMs, so a legitimately new second connection was NOT evicted while the
    // prior owner was silently overwritten (duplicate/leaked stream). Injectable for tests.
    private val nowMs: () -> Long = MonotonicClock::nowMs,
) {

    data class Owner(val deviceId: String, val socketKey: String, val acquiredMs: Long)

    private val owners = ConcurrentHashMap<String, Owner>()

    /** Returns the previous owner (if any) to be evicted by the caller. */
    @Synchronized
    fun acquire(deviceId: String, socketKey: String): Owner? {
        val now = nowMs()
        val previous = owners[deviceId]
        owners[deviceId] = Owner(deviceId, socketKey, now)
        if (previous == null) return null
        if (previous.socketKey == socketKey) return null
        if (now - previous.acquiredMs < graceMs) return null
        return previous
    }

    @Synchronized
    fun release(deviceId: String, socketKey: String) {
        val cur = owners[deviceId] ?: return
        if (cur.socketKey == socketKey) owners.remove(deviceId)
    }

    fun currentOwner(deviceId: String): Owner? = owners[deviceId]

    fun activeOwners(): List<Owner> = owners.values.toList()
}
