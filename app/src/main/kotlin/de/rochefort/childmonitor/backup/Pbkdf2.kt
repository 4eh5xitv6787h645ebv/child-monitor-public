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

import java.nio.CharBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #310: PBKDF2 (RFC 2898) over a standard JDK [Mac]. We implement the (simple, well-defined)
 * iteration/XOR construction ourselves rather than use `SecretKeyFactory("PBKDF2WithHmacSHA256")`
 * because that algorithm name only exists on API 26+, while this app's minSdk is 21 — whereas
 * `Mac("HmacSHA256")` is available on every API level. The underlying primitives (HMAC-SHA256) are
 * still the JDK's; only the standard outer loop is ours. Correctness is pinned by the RFC 6070
 * test vectors (exercised via HmacSHA1) in the unit tests. Pure / JVM-testable.
 */
object Pbkdf2 {

    /** Derive [dkLenBytes] of key material from [password]/[salt] with [iterations] of [hmacAlg]. */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLenBytes: Int,
        hmacAlg: String = "HmacSHA256",
    ): ByteArray {
        require(iterations > 0) { "iterations must be > 0" }
        require(dkLenBytes > 0) { "dkLenBytes must be > 0" }
        val mac = Mac.getInstance(hmacAlg)
        mac.init(SecretKeySpec(password, hmacAlg))
        val hLen = mac.macLength
        val blocks = (dkLenBytes + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val intBuf = ByteArray(4)
        for (i in 1..blocks) {
            // INT_32_BE(i)
            intBuf[0] = (i ushr 24).toByte()
            intBuf[1] = (i ushr 16).toByte()
            intBuf[2] = (i ushr 8).toByte()
            intBuf[3] = i.toByte()
            // U_1 = PRF(password, salt || INT(i))
            mac.update(salt)
            mac.update(intBuf)
            var u = mac.doFinal()
            val t = u.copyOf()
            // U_c = PRF(password, U_{c-1}); T_i = U_1 xor U_2 xor … xor U_iterations
            for (c in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLenBytes)
    }

    /**
     * Convenience for a [CharArray] passphrase: encodes it to UTF-8, derives with HMAC-SHA256, then
     * zeroes the transient byte copy. (The caller still owns clearing the original CharArray.)
     */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, dkLenBytes: Int): ByteArray {
        val pwBytes = charsToUtf8(password)
        return try {
            derive(pwBytes, salt, iterations, dkLenBytes, "HmacSHA256")
        } finally {
            pwBytes.fill(0)
        }
    }

    /** Encode chars to UTF-8 bytes without materialising an immutable String of the secret. */
    private fun charsToUtf8(chars: CharArray): ByteArray {
        val cb = CharBuffer.wrap(chars)
        val bb = Charsets.UTF_8.encode(cb)
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        // Best-effort wipe of the intermediate ByteBuffer's backing array if accessible.
        if (bb.hasArray()) bb.array().fill(0)
        return bytes
    }
}
