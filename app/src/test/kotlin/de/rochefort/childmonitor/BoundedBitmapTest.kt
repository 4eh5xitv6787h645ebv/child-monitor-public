package de.rochefort.childmonitor

import java.io.ByteArrayInputStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedBitmapTest {

    @Test fun `sample size is 1 for an image within the cap`() {
        assertEquals(1, BoundedBitmap.computeInSampleSize(640, 480, maxDimension = 2048))
        assertEquals(1, BoundedBitmap.computeInSampleSize(2048, 2048, maxDimension = 2048))
    }

    @Test fun `sample size grows by powers of two past the cap`() {
        assertEquals(2, BoundedBitmap.computeInSampleSize(4096, 3000, maxDimension = 2048))
        assertEquals(4, BoundedBitmap.computeInSampleSize(8192, 8192, maxDimension = 2048))
        assertEquals(8, BoundedBitmap.computeInSampleSize(16384, 100, maxDimension = 2048))
    }

    @Test fun `sample size uses the larger dimension`() {
        // 10000 wide forces downsampling even though height is tiny.
        assertEquals(8, BoundedBitmap.computeInSampleSize(10000, 50, maxDimension = 2048))
    }

    @Test fun `degenerate dimensions return sample size 1`() {
        assertEquals(1, BoundedBitmap.computeInSampleSize(0, 0, maxDimension = 2048))
        assertEquals(1, BoundedBitmap.computeInSampleSize(640, 480, maxDimension = 0))
    }

    @Test fun `readBounded returns the full payload when under the cap`() {
        val data = ByteArray(5000) { (it % 256).toByte() }
        val out = BoundedBitmap.readBounded(ByteArrayInputStream(data), maxBytes = 1_000_000)
        assertTrue(out != null && out.contentEquals(data))
    }

    @Test fun `readBounded returns the payload at exactly the cap`() {
        val data = ByteArray(1000) { 1 }
        val out = BoundedBitmap.readBounded(ByteArrayInputStream(data), maxBytes = 1000)
        assertTrue(out != null && out.size == 1000)
    }

    @Test fun `readBounded rejects an over-cap payload instead of buffering it`() {
        val data = ByteArray(2000) { 1 }
        assertNull(BoundedBitmap.readBounded(ByteArrayInputStream(data), maxBytes = 1000))
    }

    @Test fun `readBounded handles an empty stream`() {
        val out = BoundedBitmap.readBounded(ByteArrayInputStream(ByteArray(0)), maxBytes = 1000)
        assertTrue(out != null && out.isEmpty())
    }
}
