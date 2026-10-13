package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#237] The raw audio accept loop must bound how many pre-auth connection handlers can be
 * in flight so a TCP flood cannot spawn unbounded threads.
 */
class ConnectionLimiterTest {

    @Test
    fun admitsUpToGlobalCapThenRejects() {
        val l = ConnectionLimiter(maxTotal = 3, maxPerIp = 10)
        assertTrue(l.tryAcquire("a"))
        assertTrue(l.tryAcquire("b"))
        assertTrue(l.tryAcquire("c"))
        assertFalse(l.tryAcquire("d")) // global cap hit
        assertEquals(3, l.activeTotal())
    }

    @Test
    fun enforcesPerIpCap_independentOfOtherIps() {
        val l = ConnectionLimiter(maxTotal = 100, maxPerIp = 2)
        assertTrue(l.tryAcquire("1.2.3.4"))
        assertTrue(l.tryAcquire("1.2.3.4"))
        assertFalse(l.tryAcquire("1.2.3.4")) // per-IP cap hit
        assertTrue(l.tryAcquire("5.6.7.8"))  // a different IP is unaffected
    }

    @Test
    fun releaseFreesBothGlobalAndPerIpSlots() {
        val l = ConnectionLimiter(maxTotal = 1, maxPerIp = 1)
        assertTrue(l.tryAcquire("a"))
        assertFalse(l.tryAcquire("a"))
        l.release("a")
        assertEquals(0, l.activeTotal())
        assertTrue(l.tryAcquire("a")) // slot freed
    }

    @Test
    fun releaseWithoutAcquire_neverGoesNegative() {
        val l = ConnectionLimiter(maxTotal = 2, maxPerIp = 2)
        l.release("ghost")
        assertEquals(0, l.activeTotal())
        assertTrue(l.tryAcquire("a"))
        assertEquals(1, l.activeTotal())
    }
    @Test
    fun releasingAnUnknownIpDoesNotFreeAnotherParentsSlot() {
        val limiter = ConnectionLimiter(maxTotal = 1, maxPerIp = 1)
        assertTrue(limiter.tryAcquire("parent-a"))
        limiter.release("unknown")
        assertEquals(1, limiter.activeTotal())
        assertFalse(limiter.tryAcquire("parent-b"))
        limiter.release("parent-a")
        assertTrue(limiter.tryAcquire("parent-b"))
        limiter.release("parent-a") // duplicate release cannot steal B's newly acquired slot
        assertEquals(1, limiter.activeTotal())
        assertFalse(limiter.tryAcquire("parent-c"))
    }

}
