package de.rochefort.childmonitor.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #353: when the parent should renew the bearer inline after a fast (CMHELLO-rejection) reconnect failure. */
class ReconnectRenewalPolicyTest {

    private val now = 1_000_000_000_000L
    private val fast = ReconnectRenewalPolicy.FAST_FAILURE_THRESHOLD_MS   // 2000
    private val interval = ReconnectRenewalPolicy.MIN_RENEW_INTERVAL_MS   // 30000

    @Test fun `no refresh credential never renews`() {
        assertFalse(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = false, lastRenewAtMs = 0, nowMs = now))
    }

    @Test fun `fast failure with credential and never renewed renews`() {
        assertTrue(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = true, lastRenewAtMs = 0, nowMs = now))
    }

    @Test fun `long session is not an auth rejection`() {
        assertFalse(ReconnectRenewalPolicy.shouldRenewOnFailure(fast + 1, hasRefreshCredential = true, lastRenewAtMs = 0, nowMs = now))
        assertFalse(ReconnectRenewalPolicy.shouldRenewOnFailure(60_000, hasRefreshCredential = true, lastRenewAtMs = 0, nowMs = now))
    }

    @Test fun `fast-failure threshold is inclusive`() {
        assertTrue(ReconnectRenewalPolicy.shouldRenewOnFailure(fast, hasRefreshCredential = true, lastRenewAtMs = 0, nowMs = now))
    }

    @Test fun `negative streamed duration does not renew`() {
        assertFalse(ReconnectRenewalPolicy.shouldRenewOnFailure(-5, hasRefreshCredential = true, lastRenewAtMs = 0, nowMs = now))
    }

    @Test fun `throttled when renewed within the interval`() {
        assertFalse(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = true, lastRenewAtMs = now - 1000, nowMs = now))
        assertFalse(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = true, lastRenewAtMs = now - (interval - 1), nowMs = now))
    }

    @Test fun `renews again once past the interval`() {
        assertTrue(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = true, lastRenewAtMs = now - interval, nowMs = now))
        assertTrue(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = true, lastRenewAtMs = now - (interval + 5000), nowMs = now))
    }

    @Test fun `backwards clock jump (future lastRenewAt) is not treated as throttled`() {
        // sinceLast negative → not in [0, interval) → allowed (errs toward healing).
        assertTrue(ReconnectRenewalPolicy.shouldRenewOnFailure(100, hasRefreshCredential = true, lastRenewAtMs = now + 5000, nowMs = now))
    }
}
