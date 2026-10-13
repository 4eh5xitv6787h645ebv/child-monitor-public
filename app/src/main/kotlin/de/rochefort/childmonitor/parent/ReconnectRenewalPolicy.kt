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
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.parent

/**
 * #353: pure decision for whether to renew the bearer inline after a parent live-audio attempt
 * connected but then ended within a couple of seconds. That fast post-connect failure is the
 * signature of a CMHELLO rejection — the child closes the audio socket with `CMERROR 401` when the
 * bearer is missing/expired. When that happens and a refresh credential exists, renewing the bearer
 * and retrying immediately beats waiting out the reconnect backoff with the same stale token.
 *
 * Time-injected so it's unit-testable; the Android-coupled renew (TLS POST to /api/session/refresh)
 * lives in [SessionRenewer]. Throttled so a genuinely-revoked credential (renew keeps failing) or a
 * fast-but-non-auth drop can't spin the refresh endpoint. The refresh endpoint distinguishes a
 * transient 429 from a definitive 401, so the caller can keep a rate-limited credential while
 * surfacing a re-pair requirement for a revoked one.
 */
object ReconnectRenewalPolicy {

    /** A connected stream that ended within this many ms looks like a handshake/auth rejection
     *  rather than a real session that ran and then dropped. Mirrors the 2s "real success"
     *  threshold the reconnect loop already uses to reset its backoff. */
    const val FAST_FAILURE_THRESHOLD_MS = 2_000L

    /** Never renew more often than this — bounds repeated refresh POSTs when a credential is
     *  genuinely revoked (renew keeps returning false) or the failure isn't auth-related. */
    const val MIN_RENEW_INTERVAL_MS = 30_000L

    fun shouldRenewOnFailure(
        streamedMs: Long,
        hasRefreshCredential: Boolean,
        lastRenewAtMs: Long,
        nowMs: Long,
        fastFailureThresholdMs: Long = FAST_FAILURE_THRESHOLD_MS,
        minRenewIntervalMs: Long = MIN_RENEW_INTERVAL_MS,
    ): Boolean {
        if (!hasRefreshCredential) return false
        // Only a fast post-connect failure looks like an auth rejection; a long session that ended
        // is not a token problem, and a negative duration (clock skew) is not trustworthy.
        if (streamedMs !in 0L..fastFailureThresholdMs) return false
        // Throttle: skip if we renewed too recently. lastRenewAtMs <= 0 means "never" → allowed.
        if (lastRenewAtMs > 0L) {
            val sinceLast = nowMs - lastRenewAtMs
            if (sinceLast in 0 until minRenewIntervalMs) return false
        }
        return true
    }
}
