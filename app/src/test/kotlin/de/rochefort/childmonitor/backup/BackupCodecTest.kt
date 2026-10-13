package de.rochefort.childmonitor.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** #310: security properties of the passphrase-based backup codec. Low iterations for test speed. */
class BackupCodecTest {

    private val iters = 1000
    private val pass get() = "correct horse battery".toCharArray()

    @Test fun roundTripsVariousSizes() {
        for (n in intArrayOf(0, 1, 16, 17, 1024)) {
            val plain = ByteArray(n) { (it * 7 + 3).toByte() }
            val blob = BackupCodec.encrypt(plain, pass, iters)
            val out = BackupCodec.decrypt(blob, pass)
            assertArrayEquals("size $n must round-trip", plain, out)
        }
    }

    @Test fun wrongPassphraseFailsAuth() {
        val blob = BackupCodec.encrypt("secret".toByteArray(), pass, iters)
        assertThrows(BackupCodec.AuthFailed::class.java) {
            BackupCodec.decrypt(blob, "wrong passphrase".toCharArray())
        }
    }

    @Test fun tamperedCiphertextFailsAuth() {
        val blob = BackupCodec.encrypt("secret".toByteArray(), pass, iters)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte() // flip a tag/ct bit
        assertThrows(BackupCodec.AuthFailed::class.java) { BackupCodec.decrypt(blob, pass) }
    }

    @Test fun tamperedHeaderFailsAuth() {
        // The header (salt/iv/iterations) is GCM AAD — flipping a salt byte must fail decryption.
        val blob = BackupCodec.encrypt("secret".toByteArray(), pass, iters)
        blob[10] = (blob[10].toInt() xor 0x01).toByte() // within the salt region (offset 9..24)
        assertThrows(BackupCodec.AuthFailed::class.java) { BackupCodec.decrypt(blob, pass) }
    }

    @Test fun badMagicIsBadFormat() {
        val blob = BackupCodec.encrypt("x".toByteArray(), pass, iters)
        blob[0] = 'Z'.code.toByte()
        assertThrows(BackupCodec.BadFormat::class.java) { BackupCodec.decrypt(blob, pass) }
    }

    @Test fun unsupportedVersionIsBadFormat() {
        val blob = BackupCodec.encrypt("x".toByteArray(), pass, iters)
        blob[4] = 9
        assertThrows(BackupCodec.BadFormat::class.java) { BackupCodec.decrypt(blob, pass) }
    }

    @Test fun truncatedIsBadFormat() {
        assertThrows(BackupCodec.BadFormat::class.java) { BackupCodec.decrypt(ByteArray(5), pass) }
    }

    @Test fun twoEncryptionsOfSameInputDiffer() {
        // Random salt+iv per call → distinct ciphertext (no deterministic leakage).
        val a = BackupCodec.encrypt("same".toByteArray(), pass, iters)
        val b = BackupCodec.encrypt("same".toByteArray(), pass, iters)
        assertFalse(a.contentEquals(b))
        // …but both decrypt back to the same plaintext.
        assertArrayEquals(BackupCodec.decrypt(a, pass), BackupCodec.decrypt(b, pass))
    }

    @Test fun emptyPassphraseRejected() {
        // encrypt: a programmer/UI error → IllegalArgumentException precondition.
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encrypt("x".toByteArray(), CharArray(0), iters)
        }
        // decrypt: empty passphrase is user-entered data → stays in the documented failure taxonomy.
        val blob = BackupCodec.encrypt("x".toByteArray(), pass, iters)
        assertThrows(BackupCodec.AuthFailed::class.java) { BackupCodec.decrypt(blob, CharArray(0)) }
    }

    @Test fun nonPositiveIterationCountInHeaderIsBadFormat() {
        // Zero out the iteration field (bytes 5..8); the guard rejects it as BadFormat BEFORE any
        // key derivation, so a corrupt/hostile header can't drive PBKDF2 with a bad count.
        val blob = BackupCodec.encrypt("x".toByteArray(), pass, iters)
        for (i in 5..8) blob[i] = 0
        assertThrows(BackupCodec.BadFormat::class.java) { BackupCodec.decrypt(blob, pass) }
    }

    @Test fun absurdIterationCountIsRejectedBeforeKdf() {
        // Codex DoS guard: a valid-magic blob claiming iterations≈Int.MAX_VALUE must be rejected as
        // BadFormat WITHOUT running PBKDF2 (which would otherwise hang). Test completing fast == proof.
        val blob = BackupCodec.encrypt("x".toByteArray(), pass, iters)
        blob[5] = 0x7F; blob[6] = 0xFF.toByte(); blob[7] = 0xFF.toByte(); blob[8] = 0xFF.toByte() // Int.MAX_VALUE
        assertThrows(BackupCodec.BadFormat::class.java) { BackupCodec.decrypt(blob, pass) }
        // encrypt enforces the same bound.
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encrypt("x".toByteArray(), pass, Int.MAX_VALUE)
        }
    }

    @Test fun iterationCountRoundTrips() {
        // Encrypt at a distinct iteration count; decrypt (which reads it from the header) must succeed.
        val blob = BackupCodec.encrypt("payload".toByteArray(), pass, iterations = 2048)
        assertEquals("payload", String(BackupCodec.decrypt(blob, pass)))
        assertTrue(blob.size > 0)
    }
}
