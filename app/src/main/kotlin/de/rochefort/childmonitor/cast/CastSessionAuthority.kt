package de.rochefort.childmonitor.cast

import java.security.SecureRandom

/**
 * Issues capability URLs for a deliberately tiny media-only surface (#307).
 *
 * A cast target cannot normally attach the parent's Authorization header, so copying the full
 * parent bearer into its URL would turn browser history and renderer logs into account takeover.
 * These grants are instead process-local, limited to cast image endpoints, expire after one night,
 * and bind to the IP address of the first renderer that uses them. They are also revoked with the
 * parent pairing. Nothing here persists across a child-service restart.
 */
class CastSessionAuthority(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    data class Grant(
        val token: String,
        val parentDeviceId: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val boundClientAddress: String? = null,
    )

    private val grants = LinkedHashMap<String, Grant>()

    @Synchronized
    fun issue(parentDeviceId: String): Grant {
        require(parentDeviceId.isNotBlank())
        purgeExpired()
        grants.entries.removeAll { it.value.parentDeviceId == parentDeviceId }
        while (grants.size >= MAX_ACTIVE_GRANTS) {
            grants.remove(grants.entries.first().key)
        }
        val now = nowMs()
        val token = generateToken()
        return Grant(token, parentDeviceId, now, now + ttlMs).also { grants[token] = it }
    }

    /** Validate and atomically pin an unused grant to the first renderer IP that presents it. */
    @Synchronized
    fun validateAndBind(token: String?, clientAddress: String?): Boolean {
        if (!isTokenShapeValid(token)) return false
        val address = normalizeClientAddress(clientAddress) ?: return false
        purgeExpired()
        val grant = grants[token] ?: return false
        if (grant.boundClientAddress != null) return grant.boundClientAddress == address
        grants[token!!] = grant.copy(boundClientAddress = address)
        return true
    }

    @Synchronized
    fun revokeForParent(parentDeviceId: String): Int {
        val before = grants.size
        grants.entries.removeAll { it.value.parentDeviceId == parentDeviceId }
        return before - grants.size
    }

    @Synchronized
    fun activeCount(): Int {
        purgeExpired()
        return grants.size
    }

    @Synchronized
    private fun purgeExpired() {
        val now = nowMs()
        grants.entries.removeAll { now >= it.value.expiresAtMs }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return buildString(bytes.size * 2) {
            bytes.forEach { append(HEX[(it.toInt() ushr 4) and 0x0f]).append(HEX[it.toInt() and 0x0f]) }
        }
    }

    companion object {
        // Long enough for an overnight monitor session, far shorter and narrower than a durable
        // parent pairing. A service restart invalidates it immediately.
        const val DEFAULT_TTL_MS = 12L * 60L * 60L * 1000L
        const val MAX_ACTIVE_GRANTS = 8
        private const val TOKEN_BYTES = 24
        private const val TOKEN_HEX_LENGTH = TOKEN_BYTES * 2
        private const val HEX = "0123456789abcdef"

        fun isTokenShapeValid(token: String?): Boolean =
            token?.length == TOKEN_HEX_LENGTH && token.all { it in '0'..'9' || it in 'a'..'f' }

        fun normalizeClientAddress(raw: String?): String? {
            val value = raw?.trim()?.removePrefix("/") ?: return null
            if (value.isBlank() || value.length > 64 || value.any { it == '\r' || it == '\n' }) return null
            return value
        }
    }
}
