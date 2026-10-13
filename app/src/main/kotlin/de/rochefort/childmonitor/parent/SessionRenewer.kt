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

import android.content.Context
import android.util.Log
import de.rochefort.childmonitor.ParentSessionStore
import de.rochefort.childmonitor.ParentIdentity
import de.rochefort.childmonitor.PinnedHttp
import de.rochefort.childmonitor.NetworkEndpoint
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * #348 part 2: parent-side durable-session renewal. Bootstraps a persistent refresh credential
 * while the bearer is valid, and redeems it for a fresh bearer once the bearer has expired — all
 * over the pinned TLS channel. This lets a parent paired once reconnect for months without
 * re-pairing the hard-to-reach (wall-mounted) child. The credential remains valid until the user
 * explicitly unpairs/revokes this parent; a child IP change never affects it.
 *
 * Blocking / network — call from a background thread. The endpoints are TLS-or-loopback only, so a
 * device that was never TLS-pinned (cleartext-only) silently opts out of durable renewal.
 */
object SessionRenewer {

    enum class RenewResult { RENEWED, RATE_LIMITED, REVOKED, UNAVAILABLE, TRANSIENT_FAILURE }

    private const val TAG = "SessionRenewer"
    private const val TIMEOUT_MS = 4000
    private const val DEFAULT_REFRESH_TTL_MS = Long.MAX_VALUE
    private const val MIN_MINT_RETRY_MS = 5L * 60 * 1000 // back off repeated failed mint attempts
    // In-memory (per-process) last mint-attempt time per child — throttles failed mints; resets on
    // restart so a fresh boot retries promptly. Bounded by the number of paired children.
    private val lastMintAttemptMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Mint or re-mint the refresh credential when due (none stored, or past half its TTL). Requires
     * a currently-valid bearer (resolved on the child to the same deviceId) and a TLS pin. The
     * bearer is presented over the pinned channel; the returned plaintext credential is stored.
     * Best-effort: any failure is logged and ignored (the bearer keeps working meanwhile).
     */
    @Synchronized
    fun bootstrapIfNeeded(
        context: Context,
        childDeviceId: String?,
        addr: String,
        nowMs: Long,
        force: Boolean = false,
    ): Boolean {
        if (childDeviceId.isNullOrBlank()) return false
        val bearer = ParentSessionStore.get(context, childDeviceId) ?: return false
        // Refresh endpoints are TLS-only; no pin → can't (and shouldn't) use them.
        if (ParentSessionStore.fingerprintFor(context, childDeviceId) == null) return false
        val issuedAt = ParentSessionStore.refreshIssuedAt(context, childDeviceId)
        val ttl = ParentSessionStore.refreshTtlMs(context, childDeviceId)
        val legacyIdentity = !ParentSessionStore.hasRefreshDeviceId(context, childDeviceId)
        if (!force && !legacyIdentity &&
            ParentSessionStore.getRefreshToken(context, childDeviceId) != null &&
            !RefreshRenewalPolicy.shouldMint(nowMs, issuedAt, ttl)) return true
        // Throttle repeated mint ATTEMPTS so a child that never issues a credential (old build →
        // 501, or persistent error) isn't hit every 60s poll. The first attempt fires promptly
        // (no prior timestamp); a success persists issuedAt so shouldMint() then short-circuits.
        val lastAttempt = lastMintAttemptMs[childDeviceId] ?: 0L
        if (!force && nowMs - lastAttempt < MIN_MINT_RETRY_MS) {
            return ParentSessionStore.getRefreshToken(context, childDeviceId) != null
        }
        lastMintAttemptMs[childDeviceId] = nowMs

        val port = ParentSessionStore.httpsPortFor(context, childDeviceId) ?: DEFAULT_HTTPS_PORT
        val url = "https://${NetworkEndpoint.hostForAuthority(addr)}:$port/api/session/refresh-credential"
        var conn: HttpURLConnection? = null
        try {
            conn = PinnedHttp.openPinned(context, url, childDeviceId)
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("X-Parent-Device-Id", ParentIdentity.getId(context))
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.use { it.bufferedReader().readText() }
                val o = JSONObject(body)
                val token = o.optString("refreshToken")
                // Don't trust the TTL blindly: a 0/negative value would make shouldMint() always
                // true → re-mint every poll. Clamp to a sane default if missing/garbage.
                val respTtl = o.optLong("ttlMs", 0L).takeIf { it > 0L } ?: DEFAULT_REFRESH_TTL_MS
                if (token.isNotBlank()) {
                    if (ParentSessionStore.putRefreshToken(
                            context, childDeviceId, token, nowMs, respTtl,
                            refreshDeviceId = o.optString("deviceId").takeIf { it.isNotBlank() } ?: childDeviceId,
                            bearerToken = o.optString("token").takeIf { it.isNotBlank() },
                        )) {
                        Log.i(TAG, "Refresh credential minted (ttlMs=$respTtl)")
                        return true
                    }
                    Log.w(TAG, "Refresh credential rejected by secure local storage")
                    return false
                }
            } else {
                drain(conn)
                Log.d(TAG, "refresh-credential mint failed: HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "refresh-credential mint error: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return !force && ParentSessionStore.getRefreshToken(context, childDeviceId) != null
    }

    /**
     * Redeem the stored refresh credential for a fresh bearer (used when the bearer has expired).
     * On success the new bearer is stored and true is returned. #354: the child now distinguishes
     * 429 (transient rate-limit → keep the credential, back off) from 401 (genuinely revoked/invalid
     * → drop the credential via [ParentSessionStore.forgetRefresh] so the device stops futile retries
     * and the user is prompted to re-pair). Any other non-200 is treated as transient (credential kept).
     */
    fun renew(context: Context, childDeviceId: String?, addr: String): Boolean =
        renewWithResult(context, childDeviceId, addr) == RenewResult.RENEWED

    /** Coalesce late 401 responses with a renewal that has already completed on another thread. */
    @Synchronized
    fun recoverRejectedBearer(
        context: Context, childDeviceId: String?, addr: String, rejectedBearer: String?,
    ): RenewResult {
        if (childDeviceId.isNullOrBlank()) return RenewResult.UNAVAILABLE
        val current = ParentSessionStore.get(context, childDeviceId)
        if (current != null && current != rejectedBearer) return RenewResult.RENEWED
        return renewWithResult(context, childDeviceId, addr)
    }

    /** Detailed variant so the foreground service can visibly prompt re-pair on a definitive 401. */
    @Synchronized
    fun renewWithResult(context: Context, childDeviceId: String?, addr: String): RenewResult {
        if (childDeviceId.isNullOrBlank()) return RenewResult.UNAVAILABLE
        val refresh = ParentSessionStore.getRefreshToken(context, childDeviceId)
            ?: return RenewResult.UNAVAILABLE
        val bearerAtStart = ParentSessionStore.get(context, childDeviceId)
        if (ParentSessionStore.fingerprintFor(context, childDeviceId) == null) {
            return RenewResult.UNAVAILABLE
        }

        val port = ParentSessionStore.httpsPortFor(context, childDeviceId) ?: DEFAULT_HTTPS_PORT
        val url = "https://${NetworkEndpoint.hostForAuthority(addr)}:$port/api/session/refresh?deviceId=" +
            URLEncoder.encode(ParentSessionStore.refreshDeviceId(context, childDeviceId), "UTF-8")
        var conn: HttpURLConnection? = null
        try {
            conn = PinnedHttp.openPinned(context, url, childDeviceId)
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("X-Refresh-Token", refresh)
            conn.connect()
            when (RefreshRenewPolicy.classify(conn.responseCode)) {
                RefreshRenewPolicy.Outcome.RENEW -> {
                    val body = conn.inputStream.use { it.bufferedReader().readText() }
                    val token = JSONObject(body).optString("token")
                    if (token.isNotBlank()) {
                        if (ParentSessionStore.putRenewedBearer(
                                context, childDeviceId, token, refresh, bearerAtStart)) {
                            Log.i(TAG, "Bearer renewed from refresh credential")
                            return RenewResult.RENEWED
                        }
                        val current = ParentSessionStore.get(context, childDeviceId)
                        if (current != null && current != bearerAtStart) return RenewResult.RENEWED
                        Log.w(TAG, "Renewed bearer rejected by secure local storage")
                        return RenewResult.UNAVAILABLE
                    }
                    return RenewResult.TRANSIENT_FAILURE
                }
                RefreshRenewPolicy.Outcome.RATE_LIMITED -> {
                    drain(conn)
                    Log.d(TAG, "refresh redeem rate-limited (429) — keeping credential, will retry")
                    return RenewResult.RATE_LIMITED
                }
                RefreshRenewPolicy.Outcome.REVOKED -> {
                    drain(conn)
                    if (!ParentSessionStore.forgetRejectedRefresh(
                            context, childDeviceId, refresh, bearerAtStart)) {
                        return RenewResult.TRANSIENT_FAILURE // a concurrent re-pair superseded this request
                    }
                    Log.w(TAG, "refresh credential revoked (401) — dropped; re-pair needed")
                    return RenewResult.REVOKED
                }
                RefreshRenewPolicy.Outcome.TRANSIENT -> {
                    drain(conn)
                    Log.d(TAG, "refresh redeem failed: HTTP ${conn.responseCode} — keeping credential")
                    return RenewResult.TRANSIENT_FAILURE
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "refresh redeem error: ${e.message}")
            return RenewResult.TRANSIENT_FAILURE
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Drain the error body so the socket can return to the keep-alive pool. */
    private fun drain(conn: HttpURLConnection) {
        try { conn.errorStream?.use { it.copyTo(ByteArrayOutputStream()) } } catch (_: Exception) {}
    }

    private const val DEFAULT_HTTPS_PORT = 11443
}
