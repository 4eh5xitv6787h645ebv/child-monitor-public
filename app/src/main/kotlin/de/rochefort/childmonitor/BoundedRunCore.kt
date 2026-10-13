package de.rochefort.childmonitor

import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Generic bounded-concurrency bookkeeping behind [BoundedAsyncRunner] (#207),
 * extracted so the admit / refuse / release / leak-reclaim logic is unit-testable
 * without NanoHTTPD or real threads — inject [spawn] (a no-op or throwing stub in
 * tests, a daemon-thread starter in production).
 *
 * Under the cap, [submit] admits the item (adds it to the running set and spawns
 * it) exactly like an unbounded runner; over the cap it refuses (returns false,
 * spawns nothing). [completed] frees the slot when the item finishes, and is
 * idempotent. Crucially, the slot is also reclaimed if [spawn] throws (e.g. the
 * OS refuses a new thread under memory pressure), so a failed spawn can never
 * permanently burn a permit and wedge the server.
 */
class BoundedRunCore<T>(
    maxConcurrent: Int,
    private val spawn: (T, String) -> Unit
) {
    private val permits = Semaphore(maxConcurrent)
    private val running = Collections.synchronizedList(ArrayList<T>())
    private val counter = AtomicLong(0)

    /** @return true if admitted + spawned, false if at capacity. */
    fun submit(item: T, threadNamePrefix: String): Boolean {
        if (!permits.tryAcquire()) return false
        running.add(item)
        try {
            spawn(item, "$threadNamePrefix-${counter.incrementAndGet()}")
        } catch (t: Throwable) {
            // A failed spawn would otherwise leak this permit forever; reclaim it.
            running.remove(item)
            permits.release()
            throw t
        }
        return true
    }

    /** Free the slot held by [item]. Idempotent: a second call (or a call for an
     *  item that was never admitted) does not over-release. */
    fun completed(item: T) {
        if (running.remove(item)) permits.release()
    }

    /** Snapshot copy of the running items — safe to iterate while [completed]
     *  mutates the live set (used to close everything on shutdown). */
    fun runningSnapshot(): List<T> = ArrayList(running)

    fun availablePermits(): Int = permits.availablePermits()
}
