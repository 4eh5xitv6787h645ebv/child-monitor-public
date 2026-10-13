package de.rochefort.childmonitor.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #338: the parent-side replay decision logic — how a `/api/replay/audio` response maps to a
 * user-visible outcome, the request-window clamp, and clip-duration math.
 */
class ReplayAudioPolicyTest {

    @Test fun outcome_okWithBytes_isPlay() {
        assertEquals(ReplayOutcome.PLAY, ReplayAudioPolicy.outcome(httpStatus = 200, byteCount = 1600))
        assertEquals(ReplayOutcome.PLAY, ReplayAudioPolicy.outcome(httpStatus = 200, byteCount = 1))
    }

    @Test fun outcome_emptyOrNoContent_isNothingRecent() {
        // 200 with an empty body, and a 204 (the child's empty/stale answer), both mean "nothing".
        assertEquals(ReplayOutcome.NOTHING_RECENT, ReplayAudioPolicy.outcome(httpStatus = 200, byteCount = 0))
        assertEquals(ReplayOutcome.NOTHING_RECENT, ReplayAudioPolicy.outcome(httpStatus = 204, byteCount = 0))
        // 204 carries no body by definition; defend against a stray length too.
        assertEquals(ReplayOutcome.NOTHING_RECENT, ReplayAudioPolicy.outcome(httpStatus = 204, byteCount = 999))
    }

    @Test fun outcome_unauthorized_is401() {
        assertEquals(ReplayOutcome.UNAUTHORIZED, ReplayAudioPolicy.outcome(httpStatus = 401, byteCount = 0))
    }

    @Test fun outcome_anyOtherStatus_isError() {
        assertEquals(ReplayOutcome.ERROR, ReplayAudioPolicy.outcome(httpStatus = 500, byteCount = 0))
        assertEquals(ReplayOutcome.ERROR, ReplayAudioPolicy.outcome(httpStatus = 404, byteCount = 0))
        assertEquals(ReplayOutcome.ERROR, ReplayAudioPolicy.outcome(httpStatus = 403, byteCount = 10))
    }

    @Test fun clampSeconds_boundsToChildRange() {
        assertEquals(30, ReplayAudioPolicy.clampSeconds(30))
        assertEquals(1, ReplayAudioPolicy.clampSeconds(0))
        assertEquals(1, ReplayAudioPolicy.clampSeconds(-5))
        assertEquals(60, ReplayAudioPolicy.clampSeconds(120))
        assertEquals(60, ReplayAudioPolicy.clampSeconds(60))
        assertEquals(1, ReplayAudioPolicy.clampSeconds(1))
    }

    @Test fun durationMs_fromByteCount() {
        assertEquals(0L, ReplayAudioPolicy.durationMs(0))
        assertEquals(0L, ReplayAudioPolicy.durationMs(-100))
        assertEquals(1000L, ReplayAudioPolicy.durationMs(8000))   // 1s of µ-law
        assertEquals(30000L, ReplayAudioPolicy.durationMs(240000)) // 30s
    }
}
