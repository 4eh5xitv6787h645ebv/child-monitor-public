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
package de.rochefort.childmonitor.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted SHA-256 hashing for the optional in-app app-lock PIN (#375). The PIN itself is NEVER
 * stored — only `SHA-256(saltHex + pin)` as lowercase hex, alongside a per-install random salt.
 * (RefreshTokenHasher is unsalted SHA-256; a PIN's tiny keyspace needs a per-install salt so the
 * stored hash isn't a trivial rainbow-table lookup.) Pure + deterministic given (salt, pin) so it's
 * unit-testable; only [newSaltHex] is non-deterministic.
 */
object PinHasher {

    private const val SALT_BYTES = 16

    /** A fresh 16-byte random salt as 32-char lowercase hex. */
    fun newSaltHex(): String = SecureRandom().let { rng ->
        ByteArray(SALT_BYTES).also { rng.nextBytes(it) }.toHex()
    }

    /** `SHA-256(saltHex + pin)` as 64-char lowercase hex. */
    fun hash(saltHex: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((saltHex + pin).toByteArray(Charsets.UTF_8))
            .toHex()

    /** Constant-time check that [pin] under [saltHex] matches [expectedHash]; false if any is blank. */
    fun matches(saltHex: String?, pin: String, expectedHash: String?): Boolean {
        if (saltHex.isNullOrEmpty() || expectedHash.isNullOrEmpty()) return false
        val actual = hash(saltHex, pin).toByteArray(Charsets.UTF_8)
        val expected = expectedHash.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
