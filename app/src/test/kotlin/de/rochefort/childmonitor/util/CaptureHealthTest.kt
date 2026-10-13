package de.rochefort.childmonitor.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #299: audio-capture watchdog restart decision. */
class CaptureHealthTest {

    private val stale = 45_000L

    @Test fun restartsWhenStaleWhileStreaming() {
        assertTrue(CaptureHealth.audioNeedsRestart(recording = true, hasParents = true, lastDataMs = 1_000L, nowMs = 1_000L + stale, staleMs = stale))
    }

    @Test fun freshDataDoesNotRestart() {
        assertFalse(CaptureHealth.audioNeedsRestart(recording = true, hasParents = true, lastDataMs = 1_000L, nowMs = 1_000L + stale - 1, staleMs = stale))
    }

    @Test fun neverBeforeAnyData() {
        // lastDataMs == 0 → just started, don't restart even if nowMs is large
        assertFalse(CaptureHealth.audioNeedsRestart(recording = true, hasParents = true, lastDataMs = 0L, nowMs = 999_999L, staleMs = stale))
    }

    @Test fun noRestartWhenNotStreamingOrNoParents() {
        assertFalse(CaptureHealth.audioNeedsRestart(recording = false, hasParents = true, lastDataMs = 1L, nowMs = 999_999L, staleMs = stale))
        assertFalse(CaptureHealth.audioNeedsRestart(recording = true, hasParents = false, lastDataMs = 1L, nowMs = 999_999L, staleMs = stale))
    }

    @Test fun backwardClockNeverRestarts() {
        // Defensive: if now precedes the last read (clock should be monotonic, but guard anyway),
        // the elapsed diff is negative and must never read as stale.
        assertFalse(CaptureHealth.audioNeedsRestart(recording = true, hasParents = true, lastDataMs = 10_000L, nowMs = 1L, staleMs = stale))
    }
}
