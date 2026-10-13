package de.rochefort.childmonitor.security

/**
 * Pair-once policy: a refresh credential remains valid until explicit unpairing
 * or revocation. IP changes are recovered independently through the child's
 * stable device ID and therefore never invalidate this credential.
 */
object DurablePairingPolicy {
    const val PERMANENT_EXPIRY_MS: Long = Long.MAX_VALUE
    const val PERMANENT_TTL_MS: Long = Long.MAX_VALUE

    /** Upgrade every legacy time-limited stored credential without changing its secret. */
    fun normalizedExpiry(@Suppress("UNUSED_PARAMETER") storedExpiryMs: Long): Long =
        PERMANENT_EXPIRY_MS

    /** A secure QR has everything required to mint the durable credential immediately. */
    fun shouldBootstrapRefresh(token: String?, fingerprint: String?, httpsPort: Int?): Boolean =
        !token.isNullOrBlank() &&
            !fingerprint.isNullOrBlank() &&
            httpsPort != null && httpsPort in 1..65535
}
