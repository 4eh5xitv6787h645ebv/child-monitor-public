package de.rochefort.childmonitor.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #348 part 2: when the parent should (re)mint its durable refresh credential. */
class RefreshRenewalPolicyTest {

    private val now = 1_000_000_000_000L
    private val ttl = 365L * 24 * 60 * 60 * 1000 // 1 year

    @Test fun `mints when no credential exists`() {
        assertTrue(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = 0L, ttlMs = ttl))
        assertTrue(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = -1L, ttlMs = ttl))
    }

    @Test fun `mints when ttl is unknown`() {
        assertTrue(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now - 1000, ttlMs = 0L))
    }

    @Test fun `does not re-mint a fresh credential`() {
        assertFalse(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now - 1000, ttlMs = ttl))
        assertFalse(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now - (ttl / 4), ttlMs = ttl))
    }

    @Test fun `re-mints once past half ttl`() {
        assertTrue(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now - (ttl / 2), ttlMs = ttl))
        assertTrue(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now - (ttl / 2) - 1000, ttlMs = ttl))
    }

    @Test fun `half-ttl boundary is inclusive`() {
        assertTrue(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now - ttl / 2, ttlMs = ttl))
    }

    @Test fun `backwards clock jump does not force a re-mint`() {
        // issuedAt in the "future" relative to now → negative age → not yet due.
        assertFalse(RefreshRenewalPolicy.shouldMint(now, issuedAtMs = now + 5000, ttlMs = ttl))
    }

    @Test fun `permanent credential never reaches its remint boundary`() {
        assertFalse(
            RefreshRenewalPolicy.shouldMint(
                nowMs = Long.MAX_VALUE - 1,
                issuedAtMs = 1_000L,
                ttlMs = Long.MAX_VALUE
            )
        )
    }
}
