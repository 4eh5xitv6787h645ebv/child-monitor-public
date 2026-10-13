package de.rochefort.childmonitor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #348: refresh-token hashing — stable, distinct, and constant-time-verified. */
class RefreshTokenHasherTest {

    @Test fun `hash is 64-hex-char sha256`() {
        val h = RefreshTokenHasher.hash("abc123")
        assertEquals(64, h.length)
        assertTrue(h.all { it in "0123456789abcdef" })
    }

    @Test fun `hash is deterministic`() {
        assertEquals(RefreshTokenHasher.hash("token-xyz"), RefreshTokenHasher.hash("token-xyz"))
    }

    @Test fun `different tokens hash differently`() {
        assertNotEquals(RefreshTokenHasher.hash("token-a"), RefreshTokenHasher.hash("token-b"))
    }

    @Test fun `matches accepts the correct token`() {
        val token = "a-very-secret-refresh-token"
        assertTrue(RefreshTokenHasher.matches(token, RefreshTokenHasher.hash(token)))
    }

    @Test fun `matches rejects a wrong token`() {
        val hash = RefreshTokenHasher.hash("the-real-one")
        assertFalse(RefreshTokenHasher.matches("a-forgery", hash))
    }

    @Test fun `matches rejects null or empty expected hash`() {
        assertFalse(RefreshTokenHasher.matches("anything", null))
        assertFalse(RefreshTokenHasher.matches("anything", ""))
    }

    @Test fun `known sha256 vector`() {
        // SHA-256("abc") — guards against accidental algorithm/encoding changes.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RefreshTokenHasher.hash("abc")
        )
    }
}
