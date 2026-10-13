package de.rochefort.childmonitor.network

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionOwnershipGuardTest {

    @Test fun `first acquire returns no previous owner`() {
        val g = SessionOwnershipGuard(graceMs = 0L)
        assertNull(g.acquire("parent-a", "sock-1"))
    }

    @Test fun `second acquire from same device returns previous owner`() {
        val g = SessionOwnershipGuard(graceMs = 0L)
        g.acquire("parent-a", "sock-1")
        Thread.sleep(5)
        val evict = g.acquire("parent-a", "sock-2")
        assertNotNull(evict)
    }

    @Test fun `acquire within grace window returns null to allow handoff`() {
        val g = SessionOwnershipGuard(graceMs = 10_000L)
        g.acquire("parent-a", "sock-1")
        val evict = g.acquire("parent-a", "sock-2")
        assertNull(evict)
    }

    @Test fun `release matching socket clears owner`() {
        val g = SessionOwnershipGuard(graceMs = 0L)
        g.acquire("parent-a", "sock-1")
        g.release("parent-a", "sock-1")
        assertNull(g.currentOwner("parent-a"))
    }

    @Test fun `grace window uses the injected monotonic clock`() {
        var t = 1000L
        val g = SessionOwnershipGuard(graceMs = 100L, nowMs = { t })
        assertNull(g.acquire("parent-a", "sock-1")) // t=1000, first owner
        t = 1050L                                    // 50ms later: within grace window
        assertNull(g.acquire("parent-a", "sock-2")) // handoff allowed; owner becomes sock-2@1050
        t = 1200L                                    // 150ms after sock-2: beyond grace
        assertNotNull(g.acquire("parent-a", "sock-3")) // prior owner evicted
    }

    @Test fun `monotonic clock makes eviction immune to a wall-clock regression`() {
        // The bug: with wall-clock time, a backward step made (now - acquiredMs) negative,
        // i.e. < graceMs, so the second connection was wrongly NOT evicted. A monotonic clock
        // never goes backward, so a fresh connection past the grace window always evicts.
        var t = 5_000L
        val g = SessionOwnershipGuard(graceMs = 0L, nowMs = { t })
        g.acquire("parent-a", "sock-1")
        t = 5_001L
        assertNotNull(g.acquire("parent-a", "sock-2"))
    }
}
