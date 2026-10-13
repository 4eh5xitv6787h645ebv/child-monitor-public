package de.rochefort.childmonitor.security

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRateLimiterTest {

    @Test fun `allows up to max attempts`() {
        val r = AuthRateLimiter(maxAttempts = 3, windowMs = 1_000L, lockoutMs = 1_000L)
        assertTrue(r.allow("x"))
        assertTrue(r.allow("x"))
        assertTrue(r.allow("x"))
    }

    @Test fun `locks out on burst`() {
        val r = AuthRateLimiter(maxAttempts = 3, windowMs = 1_000L, lockoutMs = 60_000L)
        repeat(3) { r.allow("x") }
        assertFalse(r.allow("x"))
        assertFalse(r.allow("x"))
    }

    @Test fun `separate keys do not interfere`() {
        val r = AuthRateLimiter(maxAttempts = 1, windowMs = 1_000L, lockoutMs = 1_000L)
        assertTrue(r.allow("a"))
        assertFalse(r.allow("a"))
        assertTrue(r.allow("b"))
    }

    @Test fun `key map stays bounded under a flood of distinct keys`() {
        // #348: a flood of unique deviceIds must not grow the map without limit.
        val r = AuthRateLimiter(maxAttempts = 5, windowMs = 60_000L, lockoutMs = 60_000L, maxKeys = 16)
        repeat(500) { r.allow("dev-$it") }
        assertTrue(r.trackedKeys() <= 16)
    }
}
