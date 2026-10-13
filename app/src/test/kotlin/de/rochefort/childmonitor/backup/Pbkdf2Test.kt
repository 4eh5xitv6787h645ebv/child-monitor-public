package de.rochefort.childmonitor.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #310: pins the PBKDF2 construction against the published RFC 6070 PBKDF2-HMAC-SHA1 vectors. If the
 * iteration/XOR loop is correct for SHA1 it is correct for SHA256 (same construction, different PRF),
 * so this proves the hand-rolled outer loop without needing memorised SHA256 vectors.
 */
class Pbkdf2Test {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun derive(p: String, s: String, c: Int, dk: Int) =
        Pbkdf2.derive(p.toByteArray(Charsets.US_ASCII), s.toByteArray(Charsets.US_ASCII), c, dk, "HmacSHA1")

    @Test fun rfc6070_c1() {
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", hex(derive("password", "salt", 1, 20)))
    }

    @Test fun rfc6070_c2() {
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", hex(derive("password", "salt", 2, 20)))
    }

    @Test fun rfc6070_c4096() {
        assertEquals("4b007901b765489abead49d926f721d065a429c1", hex(derive("password", "salt", 4096, 20)))
    }

    @Test fun rfc6070_multiBlock_exercisesBlockIndexAboveOne() {
        // dkLen=25 > SHA1 hLen=20 → 2 output blocks, so this pins INT_32_BE(i) for i=2 and the
        // (i-1)*hLen assembly that the single-block vectors above never reach.
        assertEquals(
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            hex(derive("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 25)),
        )
    }

    @Test fun sha256_matchesJdkReference_multiBlock() {
        // Cross-check the actual production PRF (HMAC-SHA256) AND a 3-block output against the JDK's
        // own PBKDF2WithHmacSHA256 — proves the SHA256 path + i=2,3 blocks without a transcribed vector.
        val pw = "correct horse battery staple"
        val salt = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val iters = 1000
        val dkBytes = 70 // > 2*32 → 3 SHA-256 blocks
        val mine = Pbkdf2.derive(pw.toByteArray(Charsets.US_ASCII), salt, iters, dkBytes, "HmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, iters, dkBytes * 8)
        val jdk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        assertEquals(hex(jdk), hex(mine))
    }

    @Test fun sha256_isDeterministicAndCorrectLength() {
        val salt = "salt".toByteArray()
        val a = Pbkdf2.derive("pw".toByteArray(), salt, 1000, 32, "HmacSHA256")
        val b = Pbkdf2.derive("pw".toByteArray(), salt, 1000, 32, "HmacSHA256")
        assertEquals(32, a.size)
        assertEquals(hex(a), hex(b)) // deterministic for same inputs
    }

    @Test fun differentPasswordOrSaltChangesKey() {
        val k1 = Pbkdf2.derive("pw1".toByteArray(), "s".toByteArray(), 1000, 32)
        val k2 = Pbkdf2.derive("pw2".toByteArray(), "s".toByteArray(), 1000, 32)
        val k3 = Pbkdf2.derive("pw1".toByteArray(), "t".toByteArray(), 1000, 32)
        org.junit.Assert.assertNotEquals(hex(k1), hex(k2))
        org.junit.Assert.assertNotEquals(hex(k1), hex(k3))
    }

    @Test fun deriveKeyFromCharArrayMatchesByteArray() {
        val salt = "salt".toByteArray()
        val fromChars = Pbkdf2.deriveKey("password".toCharArray(), salt, 1000, 32)
        val fromBytes = Pbkdf2.derive("password".toByteArray(Charsets.UTF_8), salt, 1000, 32, "HmacSHA256")
        assertEquals(hex(fromBytes), hex(fromChars))
    }
}
