package de.rochefort.childmonitor.security

/**
 * #298: pure session-lifecycle policy — absolute TTL, idle (stale) expiry, and a rotation hint.
 * Time-injected so it is deterministically unit-testable; [SessionAuthority] itself needs a
 * Context for persistence and so cannot be unit-tested directly without Robolectric.
 *
 * A session is valid only while BOTH hold:
 *   - it is within its absolute TTL: now < expiresAtMs, AND
 *   - it has been used recently: now - lastUsedMs < [IDLE_TIMEOUT_MS].
 *
 * Idle expiry cuts off a lost or forgotten parent device well before the 24h absolute cap, without
 * affecting an actively-connected parent (every validate() refreshes lastUsedMs). Rotation lets an
 * active parent roll to a fresh token before the absolute cap so it is not forced to re-pair daily.
 */
object SessionExpiryPolicy {

    /** Hard upper bound on a token's lifetime regardless of activity. */
    const val ABSOLUTE_TTL_MS = 24L * 60L * 60L * 1000L

    /** A session unused for at least this long is treated as stale and rejected. */
    const val IDLE_TIMEOUT_MS = 12L * 60L * 60L * 1000L

    /** Once a session is older than this, the parent should rotate to a fresh token. */
    const val ROTATE_AFTER_MS = 12L * 60L * 60L * 1000L

    /** True when the session must be rejected: past absolute expiry, or idle too long. */
    fun isExpired(nowMs: Long, expiresAtMs: Long, lastUsedMs: Long): Boolean =
        nowMs >= expiresAtMs || (nowMs - lastUsedMs) >= IDLE_TIMEOUT_MS

    /** True when a still-valid session is old enough that the parent should rotate its token. */
    fun shouldRotate(nowMs: Long, createdAtMs: Long): Boolean =
        (nowMs - createdAtMs) >= ROTATE_AFTER_MS
}
