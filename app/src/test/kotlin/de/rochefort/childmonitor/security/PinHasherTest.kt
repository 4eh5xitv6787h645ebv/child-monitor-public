package de.rochefort.childmonitor.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PinHasherTest {

    @Test fun `hash is deterministic 64-char lowercase hex`() {
        val h = PinHasher.hash("abcd1234abcd1234", "1357")
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h, PinHasher.hash("abcd1234abcd1234", "1357")) // deterministic
    }

    @Test fun `known SHA-256 vector (salt+pin)`() {
        // SHA-256("salt1234") — guards the salt||pin concatenation + hex encoding.
        assertEquals(
            "ea32961dbd579ef5697c367f9267921ee07f14d77fb2d4fb9500d4221d615695",
            PinHasher.hash("salt", "1234"),
        )
    }

    @Test fun `different salt or pin changes the hash`() {
        val base = PinHasher.hash("aaaa", "1234")
        assertNotEquals(base, PinHasher.hash("bbbb", "1234")) // salt matters
        assertNotEquals(base, PinHasher.hash("aaaa", "1235")) // pin matters
    }

    @Test fun `matches accepts the right pin and rejects the wrong one`() {
        val salt = "0011223344556677"
        val hash = PinHasher.hash(salt, "4242")
        assertTrue(PinHasher.matches(salt, "4242", hash))
        assertFalse(PinHasher.matches(salt, "4243", hash))
        assertFalse(PinHasher.matches("ffff", "4242", hash)) // wrong salt
    }

    @Test fun `matches is false on null or empty salt or hash`() {
        assertFalse(PinHasher.matches(null, "1234", "deadbeef"))
        assertFalse(PinHasher.matches("", "1234", "deadbeef"))
        assertFalse(PinHasher.matches("abcd", "1234", null))
        assertFalse(PinHasher.matches("abcd", "1234", ""))
    }

    @Test fun `newSaltHex is 32 hex chars and varies`() {
        val a = PinHasher.newSaltHex()
        val b = PinHasher.newSaltHex()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
    }
}
