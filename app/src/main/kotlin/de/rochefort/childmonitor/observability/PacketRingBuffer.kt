package de.rochefort.childmonitor.observability

import de.rochefort.childmonitor.reliability.MonotonicClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device packet capture ring buffer for last-60s diagnostics.
 * Implements CM-OBS-006: On-device packet capture ring buffer for last-60s diagnostics.
 *
 * Stores a trailing window of annotated network events (not raw pcap) so the
 * diagnostics screen can dump the most recent 60 seconds of reconnects,
 * socket errors, and protocol anomalies without cluttering permanent logs.
 */
class PacketRingBuffer(
    private val windowMs: Long = 60_000L,
    private val nowMs: () -> Long = MonotonicClock::nowMs
) {

    data class Entry(
        val tsMs: Long,
        val direction: String,
        val endpoint: String,
        val bytes: Int,
        val note: String?
    )

    private val deque = ConcurrentLinkedDeque<Entry>()
    private val size = AtomicInteger(0)

    fun record(direction: String, endpoint: String, bytes: Int, note: String? = null) {
        val entry = Entry(nowMs(), direction, endpoint, bytes, note)
        deque.addLast(entry)
        size.incrementAndGet()
        evict()
    }

    private fun evict() {
        val cutoff = nowMs() - windowMs
        while (true) {
            val head = deque.peekFirst() ?: return
            if (head.tsMs >= cutoff) return
            if (deque.pollFirst() != null) size.decrementAndGet()
        }
    }

    fun dump(): JSONArray {
        evict()
        val arr = JSONArray()
        for (e in deque) {
            arr.put(
                JSONObject()
                    .put("tsMs", e.tsMs)
                    .put("dir", e.direction)
                    .put("endpoint", e.endpoint)
                    .put("bytes", e.bytes)
                    .put("note", e.note)
            )
        }
        return arr
    }

    fun size(): Int = size.get()
}
