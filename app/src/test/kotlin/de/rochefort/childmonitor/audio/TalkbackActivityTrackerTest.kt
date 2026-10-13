package de.rochefort.childmonitor.audio

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkbackActivityTrackerTest {
    @Test fun `an open socket without audio cannot mute the microphone`() {
        val tracker = TalkbackActivityTracker(nowMs = { 1_000L })
        assertFalse(tracker.hasActiveFrames())
        tracker.remove("silent-parent")
        assertFalse(tracker.hasActiveFrames())
    }

    @Test fun `one parent closing preserves another parents active audio`() {
        var now = 1_000L
        val tracker = TalkbackActivityTracker(nowMs = { now })
        tracker.markFrame("parent-a")
        now += 100
        tracker.markFrame("parent-b")
        tracker.remove("parent-a")
        assertTrue(tracker.hasActiveFrames())
        tracker.remove("parent-a") // duplicate close/exception callbacks are harmless
        assertTrue(tracker.hasActiveFrames())
        tracker.remove("parent-b")
        assertFalse(tracker.hasActiveFrames())
    }

    @Test fun `a stalled talkback sender expires and can later resume`() {
        var now = 1_000L
        val tracker = TalkbackActivityTracker(idleTimeoutMs = 1_500L, nowMs = { now })
        tracker.markFrame("parent-a")
        now = 2_499L
        assertTrue(tracker.hasActiveFrames())
        now = 2_500L
        assertFalse(tracker.hasActiveFrames())
        tracker.markFrame("parent-a")
        assertTrue(tracker.hasActiveFrames())
        tracker.clear()
        assertFalse(tracker.hasActiveFrames())
    }

    @Test fun `a fresh frame from another parent preserves activity after an older sender stalls`() {
        var now = 1_000L
        val tracker = TalkbackActivityTracker(nowMs = { now })
        tracker.markFrame("stalled-parent")
        now = 2_400L
        tracker.markFrame("active-parent")
        now = 2_500L
        assertTrue(tracker.hasActiveFrames())
        tracker.remove("active-parent")
        assertFalse(tracker.hasActiveFrames())
    }
}
