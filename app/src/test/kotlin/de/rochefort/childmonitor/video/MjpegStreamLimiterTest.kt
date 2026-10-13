package de.rochefort.childmonitor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #306: concurrent MJPEG stream cap. */
class MjpegStreamLimiterTest {

    @Test fun acquiresUpToMaxThenRefuses() {
        val limiter = MjpegStreamLimiter(maxConcurrent = 2)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse("third should be refused", limiter.tryAcquire())
        assertEquals(2, limiter.activeCount())
    }

    @Test fun releaseFreesASlot() {
        val limiter = MjpegStreamLimiter(maxConcurrent = 1)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        limiter.release()
        assertTrue("slot freed after release", limiter.tryAcquire())
    }

    @Test fun releaseNeverGoesNegative() {
        val limiter = MjpegStreamLimiter(maxConcurrent = 1)
        limiter.release()
        limiter.release()
        assertEquals(0, limiter.activeCount())
        assertTrue(limiter.tryAcquire())
        assertEquals(1, limiter.activeCount())
    }

    @Test fun doubleReleaseAfterAcquire_isIdempotentAtZero() {
        val limiter = MjpegStreamLimiter(maxConcurrent = 2)
        limiter.tryAcquire()
        limiter.release()
        limiter.release() // extra release must not let an extra acquire sneak past max later
        assertEquals(0, limiter.activeCount())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test fun defaultMaxIsThree() {
        val limiter = MjpegStreamLimiter()
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }
}
