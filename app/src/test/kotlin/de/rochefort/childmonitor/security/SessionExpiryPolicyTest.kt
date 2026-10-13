package de.rochefort.childmonitor.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #298: absolute TTL + idle (stale) expiry + rotation-after-half-life. */
class SessionExpiryPolicyTest {

    private val now = 1_000_000_000_000L
    private val hour = 60L * 60L * 1000L

    @Test fun `fresh session within ttl and recently used is valid`() {
        val expiresAt = now + 23 * hour
        val lastUsed = now - 1 * hour
        assertFalse(SessionExpiryPolicy.isExpired(now, expiresAt, lastUsed))
    }

    @Test fun `session past absolute expiry is expired even if just used`() {
        val expiresAt = now - 1 // already past
        val lastUsed = now      // used right now
        assertTrue(SessionExpiryPolicy.isExpired(now, expiresAt, lastUsed))
    }

    @Test fun `expiry boundary is inclusive`() {
        // now == expiresAtMs counts as expired (>=).
        assertTrue(SessionExpiryPolicy.isExpired(now, now, now))
    }

    @Test fun `session idle longer than idle timeout is stale even within ttl`() {
        val expiresAt = now + 10 * hour // still within absolute TTL
        val lastUsed = now - SessionExpiryPolicy.IDLE_TIMEOUT_MS - 1
        assertTrue(SessionExpiryPolicy.isExpired(now, expiresAt, lastUsed))
    }

    @Test fun `session idle just under the idle timeout is still valid`() {
        val expiresAt = now + 10 * hour
        val lastUsed = now - SessionExpiryPolicy.IDLE_TIMEOUT_MS + 1
        assertFalse(SessionExpiryPolicy.isExpired(now, expiresAt, lastUsed))
    }

    @Test fun `idle timeout boundary is inclusive`() {
        val expiresAt = now + 10 * hour
        val lastUsed = now - SessionExpiryPolicy.IDLE_TIMEOUT_MS
        assertTrue(SessionExpiryPolicy.isExpired(now, expiresAt, lastUsed))
    }

    @Test fun `young session does not need rotation`() {
        val createdAt = now - 1 * hour
        assertFalse(SessionExpiryPolicy.shouldRotate(now, createdAt))
    }

    @Test fun `session past the rotation age should rotate`() {
        val createdAt = now - SessionExpiryPolicy.ROTATE_AFTER_MS - 1
        assertTrue(SessionExpiryPolicy.shouldRotate(now, createdAt))
    }

    @Test fun `rotation age boundary is inclusive`() {
        val createdAt = now - SessionExpiryPolicy.ROTATE_AFTER_MS
        assertTrue(SessionExpiryPolicy.shouldRotate(now, createdAt))
    }

    @Test fun `idle timeout is not longer than the absolute ttl`() {
        // Idle expiry must be able to fire before the absolute cap, else it's dead code.
        assertTrue(SessionExpiryPolicy.IDLE_TIMEOUT_MS <= SessionExpiryPolicy.ABSOLUTE_TTL_MS)
    }
}
