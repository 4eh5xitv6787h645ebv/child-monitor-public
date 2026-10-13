package de.rochefort.childmonitor.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTrackerTest {
    @Test fun countsOnlySamplesActuallyAcceptedByPlayback() {
        val tracker = PlaybackQueueTracker()
        tracker.recordWrite(200)
        tracker.recordWrite(50)
        assertEquals(150L, tracker.queuedFrames(100))
        assertEquals(100L, tracker.queuedFrames(150))
        assertEquals(0L, tracker.queuedFrames(250))
    }

    @Test fun signedBoundaryDoesNotLookLikeDaysOfQueuedAudio() {
        val tracker = PlaybackQueueTracker()
        tracker.recordWrite(Int.MAX_VALUE)
        tracker.recordWrite(500)
        assertEquals(500L, tracker.queuedFrames(Int.MAX_VALUE))
        assertEquals(499L, tracker.queuedFrames(Int.MIN_VALUE))
        assertEquals(200L, tracker.queuedFrames(Int.MIN_VALUE + 299))
    }

    @Test fun fullUnsignedRolloverKeepsRealQueueDepth() {
        val tracker = PlaybackQueueTracker()
        tracker.recordWrite(Int.MAX_VALUE)
        tracker.recordWrite(Int.MAX_VALUE)
        tracker.recordWrite(802)
        assertEquals(2_147_484_449L, tracker.queuedFrames(Int.MAX_VALUE))
        assertEquals(801L, tracker.queuedFrames(-1))
        assertEquals(800L, tracker.queuedFrames(0))
        assertEquals(400L, tracker.queuedFrames(400))
        assertEquals(0L, tracker.queuedFrames(800))
    }

    @Test fun flushingDiscardsOldAccountingAndAcceptsNewAudio() {
        val tracker = PlaybackQueueTracker()
        tracker.recordWrite(32_000)
        assertEquals(24_000L, tracker.queuedFrames(8_000))
        tracker.reset()
        assertEquals(0L, tracker.queuedFrames(0))
        tracker.recordWrite(800)
        assertEquals(800L, tracker.queuedFrames(0))
        assertEquals(600L, tracker.queuedFrames(200))
    }
}
