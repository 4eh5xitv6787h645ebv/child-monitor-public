/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package de.rochefort.childmonitor.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * #310: passphrase-based encrypt/decrypt for the local settings/pairings backup. AES-256-GCM with a
 * PBKDF2-HMAC-SHA256-derived key (see [Pbkdf2]); the full header (magic/version/iterations/salt/iv)
 * is bound as GCM additional-authenticated-data, so tampering with ANY of it fails decryption. The
 * blob is self-describing so a future iteration count or version can still be read back.
 *
 * Wire format (all big-endian):
 *   "CMB1" (4)  | version=1 (1) | iterations:int (4) | salt (16) | iv (12) | ciphertext+tag (…)
 *
 * Pure / JVM-testable (javax.crypto is JDK, not Android). Confidentiality + integrity come from
 * GCM; a wrong passphrase or any corruption surfaces as [AuthFailed]; a non-backup / unknown
 * version surfaces as [BadFormat].
 */
object BackupCodec {

    private val MAGIC = byteArrayOf('C'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN = 32          // AES-256
    private const val TAG_BITS = 128
    // OWASP 2024/2025 guidance for PBKDF2-HMAC-SHA256. The blob is self-describing (the count is in
    // the AAD-bound header), so this can be raised again later and old backups still decrypt.
    const val DEFAULT_ITERATIONS = 600_000

    /** Codex: bound the iteration count read from a blob. PBKDF2 runs BEFORE GCM can authenticate, so
     *  an attacker-crafted (valid-magic) blob with iterations≈Int.MAX_VALUE would otherwise hang the
     *  CPU on restore. Generous headroom over DEFAULT for future cost increases, but finite. */
    const val MAX_SUPPORTED_ITERATIONS = 10_000_000
    private const val HEADER_LEN = 4 + 1 + 4 + SALT_LEN + IV_LEN

    /** Thrown when the input isn't a recognised backup blob (bad magic / unsupported version / truncated). */
    class BadFormat(message: String) : Exception(message)

    /** Thrown when GCM authentication fails — a wrong passphrase OR a corrupted/tampered backup. */
    class AuthFailed(cause: Throwable?) : Exception("wrong passphrase or corrupted backup", cause)

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        require(iterations in 1..MAX_SUPPORTED_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val header = buildHeader(iterations, salt, iv)
        val key = Pbkdf2.deriveKey(passphrase, salt, iterations, KEY_LEN)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            val ct = cipher.doFinal(plaintext)
            return header + ct
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        // On decrypt the passphrase is user-entered DATA, so keep the failure taxonomy to the two
        // documented types: an empty passphrase can never be correct → AuthFailed (not a thrown
        // IllegalArgumentException that would escape a UI catching only BadFormat/AuthFailed).
        if (passphrase.isEmpty()) throw AuthFailed(null)
        if (blob.size < HEADER_LEN) throw BadFormat("blob too short")
        for (i in MAGIC.indices) if (blob[i] != MAGIC[i]) throw BadFormat("bad magic")
        val version = blob[4].toInt() and 0xff
        if (version != VERSION) throw BadFormat("unsupported version $version")
        val iterations = ((blob[5].toInt() and 0xff) shl 24) or
            ((blob[6].toInt() and 0xff) shl 16) or
            ((blob[7].toInt() and 0xff) shl 8) or
            (blob[8].toInt() and 0xff)
        // Reject before deriving — PBKDF2 runs before GCM auth, so an out-of-range count must not
        // reach the KDF (DoS guard, Codex).
        if (iterations !in 1..MAX_SUPPORTED_ITERATIONS) throw BadFormat("iteration count out of range")
        val salt = blob.copyOfRange(9, 9 + SALT_LEN)
        val iv = blob.copyOfRange(9 + SALT_LEN, HEADER_LEN)
        val header = blob.copyOfRange(0, HEADER_LEN)
        val ct = blob.copyOfRange(HEADER_LEN, blob.size)
        val key = Pbkdf2.deriveKey(passphrase, salt, iterations, KEY_LEN)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            return cipher.doFinal(ct)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw AuthFailed(e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw AuthFailed(e) // some providers surface GCM auth failure as BadPadding
        } finally {
            key.fill(0)
        }
    }

    private fun buildHeader(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
        val h = ByteArray(HEADER_LEN)
        System.arraycopy(MAGIC, 0, h, 0, 4)
        h[4] = VERSION.toByte()
        h[5] = (iterations ushr 24).toByte()
        h[6] = (iterations ushr 16).toByte()
        h[7] = (iterations ushr 8).toByte()
        h[8] = iterations.toByte()
        System.arraycopy(salt, 0, h, 9, SALT_LEN)
        System.arraycopy(iv, 0, h, 9 + SALT_LEN, IV_LEN)
        return h
    }
}
