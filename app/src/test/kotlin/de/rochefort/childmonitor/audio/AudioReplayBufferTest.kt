package de.rochefort.childmonitor.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** #284: rolling audio replay ring buffer. */
class AudioReplayBufferTest {

    // small buffer: 1 "second" = 10 bytes, capacity 10
    private fun buf() = AudioReplayBuffer(maxSeconds = 1, bytesPerSecond = 10)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val now = 1_000L
    private val bigGap = 1_000_000L // effectively "never stale" for the non-staleness tests

    @Test fun emptyReturnsNothing() {
        assertEquals(0, buf().lastSeconds(1, now, bigGap).size)
        assertEquals(0L, buf().availableMs(now, bigGap))
    }

    @Test fun returnsAppendedBytesInOrder() {
        val b = buf()
        b.append(bytes(1, 2, 3, 4, 5), 0, 5, now)
        assertArrayEquals(bytes(1, 2, 3, 4, 5), b.lastSeconds(1, now, bigGap))
        assertEquals(500L, b.availableMs(now, bigGap)) // 5 bytes / 10 Bps = 0.5s
    }

    @Test fun wrapsAndKeepsMostRecent() {
        val b = buf()
        b.append(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0, 10, now) // fills exactly
        b.append(bytes(11, 12, 13, 14, 15), 0, 5, now)             // wraps over oldest 5
        assertArrayEquals(bytes(6, 7, 8, 9, 10, 11, 12, 13, 14, 15), b.lastSeconds(1, now, bigGap))
        assertEquals(1000L, b.availableMs(now, bigGap)) // capped at capacity
    }

    @Test fun lastSecondsClampsToBuffered() {
        val b = buf()
        b.append(bytes(1, 2, 3), 0, 3, now)
        assertArrayEquals(bytes(1, 2, 3), b.lastSeconds(1, now, bigGap))
        assertArrayEquals(ByteArray(0), b.lastSeconds(0, now, bigGap))
    }

    @Test fun appendLargerThanCapacityKeepsTail() {
        val b = buf()
        b.append(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), 0, 12, now) // 12 > capacity 10
        assertArrayEquals(bytes(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), b.lastSeconds(1, now, bigGap))
    }

    @Test fun clearEmpties() {
        val b = buf()
        b.append(bytes(1, 2, 3), 0, 3, now)
        b.clear()
        assertEquals(0, b.lastSeconds(1, now, bigGap).size)
    }

    @Test fun respectsOffset() {
        val b = buf()
        b.append(bytes(9, 9, 1, 2, 3, 9), 2, 3, now) // bytes 1,2,3
        assertArrayEquals(bytes(1, 2, 3), b.lastSeconds(1, now, bigGap))
    }

    @Test fun staleBufferReturnsNothing() {
        val b = buf()
        b.append(bytes(1, 2, 3, 4, 5), 0, 5, nowMs = 1_000L)
        val maxGap = 60_000L
        // within the window: served
        assertArrayEquals(bytes(1, 2, 3, 4, 5), b.lastSeconds(1, nowMs = 1_000L + 60_000L, maxGapMs = maxGap))
        assertEquals(500L, b.availableMs(nowMs = 1_000L + 60_000L, maxGapMs = maxGap))
        // past the window (gap exceeded): empty, and availableMs 0 — no stale room audio served
        assertEquals(0, b.lastSeconds(1, nowMs = 1_000L + 60_001L, maxGapMs = maxGap).size)
        assertEquals(0L, b.availableMs(nowMs = 1_000L + 60_001L, maxGapMs = maxGap))
    }
}
