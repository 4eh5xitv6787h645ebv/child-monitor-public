package de.rochefort.childmonitor.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #337: rolling snapshot (JPEG frame) replay ring buffer. */
class SnapshotReplayBufferTest {

    private fun jpeg(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val bigGap = 1_000_000L // effectively "never stale" for the non-staleness tests

    @Test fun emptyReturnsNothing() {
        val b = SnapshotReplayBuffer()
        assertEquals(emptyList<Long>(), b.timestamps(1_000L, bigGap))
        assertEquals(0, b.availableCount(1_000L, bigGap))
        assertEquals(0L, b.availableMs(1_000L, bigGap))
        assertNull(b.frameAt(1_000L, 1_000L, bigGap))
    }

    @Test fun appendThenListAndFetch() {
        val b = SnapshotReplayBuffer()
        b.append(jpeg(1, 1), 100L)
        b.append(jpeg(2, 2), 200L)
        b.append(jpeg(3, 3), 300L)
        assertEquals(listOf(100L, 200L, 300L), b.timestamps(300L, bigGap))
        assertEquals(3, b.availableCount(300L, bigGap))
        assertEquals(200L, b.availableMs(300L, bigGap)) // 300 - 100
        assertArrayEquals(jpeg(2, 2), b.frameAt(200L, 300L, bigGap)?.jpeg)
        assertNull(b.frameAt(999L, 300L, bigGap)) // no frame at that timestamp
    }

    @Test fun emptyFrameIgnored() {
        val b = SnapshotReplayBuffer()
        b.append(ByteArray(0), 100L)
        assertEquals(0, b.availableCount(100L, bigGap))
    }

    @Test fun evictsByFrameCount() {
        val b = SnapshotReplayBuffer(maxFrames = 3, maxTotalBytes = Long.MAX_VALUE, maxAgeMs = Long.MAX_VALUE)
        for (i in 1..5) b.append(jpeg(i), i * 100L)
        assertEquals(listOf(300L, 400L, 500L), b.timestamps(500L, bigGap)) // oldest two dropped
        assertArrayEquals(jpeg(5), b.frameAt(500L, 500L, bigGap)?.jpeg)
        assertNull(b.frameAt(100L, 500L, bigGap))
    }

    @Test fun evictsByTotalBytes() {
        // Each frame is 4 bytes; cap at 10 bytes → at most 2 frames (8 ≤ 10; a 3rd → 12 > 10 → evict).
        val b = SnapshotReplayBuffer(maxFrames = 100, maxTotalBytes = 10L, maxAgeMs = Long.MAX_VALUE)
        b.append(jpeg(1, 1, 1, 1), 100L)
        b.append(jpeg(2, 2, 2, 2), 200L)
        b.append(jpeg(3, 3, 3, 3), 300L)
        assertEquals(listOf(200L, 300L), b.timestamps(300L, bigGap))
    }

    @Test fun evictsByAge() {
        val b = SnapshotReplayBuffer(maxFrames = 100, maxTotalBytes = Long.MAX_VALUE, maxAgeMs = 1_000L)
        b.append(jpeg(1), 0L)
        b.append(jpeg(2), 500L)
        b.append(jpeg(3), 2_000L) // newest=2000: 2000-0=2000>1000 drop; 2000-500=1500>1000 drop
        assertEquals(listOf(2_000L), b.timestamps(2_000L, bigGap))
    }

    @Test fun alwaysKeepsNewestEvenIfOverBudget() {
        val b = SnapshotReplayBuffer(maxFrames = 100, maxTotalBytes = 2L, maxAgeMs = Long.MAX_VALUE)
        b.append(jpeg(1, 2, 3, 4, 5), 100L) // 5 bytes > 2-byte budget, but it's the only frame
        assertEquals(listOf(100L), b.timestamps(100L, bigGap))
        assertArrayEquals(jpeg(1, 2, 3, 4, 5), b.frameAt(100L, 100L, bigGap)?.jpeg)
    }

    @Test fun staleReturnsNothing() {
        val b = SnapshotReplayBuffer()
        b.append(jpeg(1, 2), 1_000L)
        val maxGap = 60_000L
        // within the window: served
        assertEquals(listOf(1_000L), b.timestamps(1_000L + 60_000L, maxGap))
        assertArrayEquals(jpeg(1, 2), b.frameAt(1_000L, 1_000L + 60_000L, maxGap)?.jpeg)
        // past the window: empty + frameAt null + count 0 — no stale footage served
        assertEquals(emptyList<Long>(), b.timestamps(1_000L + 60_001L, maxGap))
        assertNull(b.frameAt(1_000L, 1_000L + 60_001L, maxGap))
        assertEquals(0, b.availableCount(1_000L + 60_001L, maxGap))
    }

    @Test fun clearEmpties() {
        val b = SnapshotReplayBuffer()
        b.append(jpeg(1, 2, 3), 100L)
        b.clear()
        assertEquals(emptyList<Long>(), b.timestamps(100L, bigGap))
        assertEquals(0, b.availableCount(100L, bigGap))
    }
}
