package de.rochefort.childmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** wz3zrw3rp Bug2: a stale-true video-liveness read must not veto the activity-side discovery fallback. */
class VideoLivenessPolicyTest {

    private val now = 1_000_000_000_000L
    private val interval = 3_000L

    @Test fun `never received a frame is not up`() {
        assertFalse(VideoLivenessPolicy.isUp(lastFrameMs = 0L, nowMs = now, snapshotIntervalMs = interval, failureStreak = 0))
    }

    @Test fun `recent frame with no failures is up`() {
        assertTrue(VideoLivenessPolicy.isUp(lastFrameMs = now - 1_000, nowMs = now, snapshotIntervalMs = interval, failureStreak = 0))
    }

    @Test fun `an active failure streak vetoes liveness even with a recent frame`() {
        // The core fix: a dead/changed IP makes fetches fail, so a recent-but-pre-change frame
        // must not count as live.
        assertFalse(VideoLivenessPolicy.isUp(lastFrameMs = now - 1_000, nowMs = now, snapshotIntervalMs = interval, failureStreak = 1))
        assertFalse(VideoLivenessPolicy.isUp(lastFrameMs = now - 1_000, nowMs = now, snapshotIntervalMs = interval, failureStreak = 5))
    }

    @Test fun `freshness window is floored at the minimum for fast cadence`() {
        // interval*2 = 1000ms < 8000ms floor → a 5s-old frame at a 500ms cadence is still up.
        assertTrue(VideoLivenessPolicy.isUp(lastFrameMs = now - 5_000, nowMs = now, snapshotIntervalMs = 500L, failureStreak = 0))
    }

    @Test fun `freshness window is capped for slow cadence`() {
        // At a 10s cadence, interval*2 = 20s, but the cap is 12s: a 15s-old frame is NOT up
        // (the old uncapped behavior would have vetoed discovery here).
        assertFalse(VideoLivenessPolicy.isUp(lastFrameMs = now - 15_000, nowMs = now, snapshotIntervalMs = 10_000L, failureStreak = 0))
        // A 10s-old frame at the same cadence is still covered (window 12s > a 10s poll gap).
        assertTrue(VideoLivenessPolicy.isUp(lastFrameMs = now - 10_000, nowMs = now, snapshotIntervalMs = 10_000L, failureStreak = 0))
    }

    @Test fun `a frame exactly at the floor window edge is not up`() {
        assertFalse(VideoLivenessPolicy.isUp(lastFrameMs = now - 8_000, nowMs = now, snapshotIntervalMs = 500L, failureStreak = 0))
        assertTrue(VideoLivenessPolicy.isUp(lastFrameMs = now - 7_999, nowMs = now, snapshotIntervalMs = 500L, failureStreak = 0))
    }

    @Test fun `negative age (clock skew) is not trusted`() {
        assertFalse(VideoLivenessPolicy.isUp(lastFrameMs = now + 5_000, nowMs = now, snapshotIntervalMs = interval, failureStreak = 0))
    }
}
