package de.rochefort.childmonitor.security

import java.security.MessageDigest

/**
 * #348: hashing for durable refresh tokens. The child stores only the SHA-256 hash of each
 * device's long-lived refresh token (never the plaintext), so a dump of the child's prefs cannot
 * yield a usable credential. Verification is constant-time ([MessageDigest.isEqual]) to avoid a
 * timing side-channel on the comparison. Pure / unit-testable.
 */
object RefreshTokenHasher {

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    /** Constant-time check that [token] hashes to [expectedHash]. */
    fun matches(token: String, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrEmpty()) return false
        val actual = hash(token).toByteArray(Charsets.US_ASCII)
        val expected = expectedHash.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(actual, expected)
    }
}
