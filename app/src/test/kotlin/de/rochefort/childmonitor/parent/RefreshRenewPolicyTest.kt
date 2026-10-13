package de.rochefort.childmonitor.parent

import org.junit.Assert.assertEquals
import org.junit.Test

/** #354: refresh-renew HTTP-status classification (429 rate-limit vs 401 revoke vs transient). */
class RefreshRenewPolicyTest {

    @Test fun ok_renews() = assertEquals(RefreshRenewPolicy.Outcome.RENEW, RefreshRenewPolicy.classify(200))

    @Test fun tooManyRequests_isRateLimited() =
        assertEquals(RefreshRenewPolicy.Outcome.RATE_LIMITED, RefreshRenewPolicy.classify(429))

    @Test fun unauthorized_isRevoked() =
        assertEquals(RefreshRenewPolicy.Outcome.REVOKED, RefreshRenewPolicy.classify(401))

    @Test fun otherCodes_areTransient() {
        listOf(403, 404, 500, 502, 503, 0, -1).forEach {
            assertEquals("HTTP $it should be transient", RefreshRenewPolicy.Outcome.TRANSIENT, RefreshRenewPolicy.classify(it))
        }
    }
}
