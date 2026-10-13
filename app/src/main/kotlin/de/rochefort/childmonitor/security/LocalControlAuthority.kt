package de.rochefort.childmonitor.security

import java.security.MessageDigest
import java.security.SecureRandom

/** Memory-only proof for the child app's own pairing screen, shared with its service. */
internal object LocalControlAuthority {
    const val HEADER = "x-child-monitor-local-admin"
    val token: String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun validates(candidate: String?): Boolean = candidate != null &&
        MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))
}
