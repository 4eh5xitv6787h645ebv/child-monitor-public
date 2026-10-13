package de.rochefort.childmonitor.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Issues and validates short-lived bearer tokens for paired parent devices.
 * Tokens are bound to deviceId and expire after [SESSION_TTL_MS].
 *
 * Addresses CM-SEC-002 (authenticated sessions) and CM-SEC-004 (key/session lifecycle).
 */
class SessionAuthority internal constructor(private val prefs: android.content.SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()
    // #298: wall-clock of the last time we flushed lastUsedMs to disk. validate() refreshes
    // lastUsedMs in memory on every request but only persists it at most once per
    // LAST_USED_PERSIST_INTERVAL_MS (coalesced across all sessions) so the on-disk value stays
    // fresh enough for idle expiry to survive a service restart, without a disk write per request.
    private var lastUsedPersistAtMs = 0L
    // Durable refresh credentials keyed by their hash, allowing several parents to use
    // the same legacy QR identity without replacing each other. Lets an
    // already-paired parent renew short-lived bearers indefinitely without the interactive PIN or
    // physical access to the child. Only the SHA-256 hash is kept — never the plaintext token.
    private val refreshCreds = ConcurrentHashMap<String, RefreshCred>()

    data class Session(
        val token: String,
        val deviceId: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val lastUsedMs: Long,
        /** Token was delivered with a QR that advertised pinned TLS audio. */
        val tlsAudioRequired: Boolean = false
    )

    data class RefreshCred(
        val deviceId: String,
        val tokenHash: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val lastUsedMs: Long,
        /** Preserve the originating session's audio transport requirement. */
        val tlsAudioRequired: Boolean = false
    )

    init {
        loadSessions()
        loadRefreshCreds()
    }

    @Synchronized
    fun issueToken(deviceId: String, tlsAudioRequired: Boolean = false): JSONObject {
        purgeExpired()
        val now = System.currentTimeMillis()
        val token = generateToken()
        val session = Session(
            token = token,
            deviceId = deviceId,
            createdAtMs = now,
            expiresAtMs = now + SESSION_TTL_MS,
            lastUsedMs = now,
            tlsAudioRequired = tlsAudioRequired
        )
        sessions[token] = session
        persistSessions()
        return JSONObject()
            .put("token", token)
            .put("deviceId", deviceId)
            .put("expiresAtMs", session.expiresAtMs)
            .put("ttlMs", SESSION_TTL_MS)
            .put("tlsAudioRequired", tlsAudioRequired)
    }

    /**
     * Validate a bearer token. Synchronized like the rest of the mutation surface
     * (review #8) — previously this was the only writer outside the monitor and
     * could race with issueToken/revokeAll/purgeExpired on persistSessions's
     * iteration of the values view.
     *
     * #298: lastUsedMs is refreshed in memory on every successful validate and flushed to disk at
     * most once per [LAST_USED_PERSIST_INTERVAL_MS] (throttled, coalesced across sessions). This
     * keeps the persisted lastUsedMs within the throttle window of reality so the new idle-expiry
     * check is correct across a service restart — without paying a disk write on every request.
     */
    @Synchronized
    fun validate(token: String?): Session? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        val now = System.currentTimeMillis()
        // #298: reject past the absolute TTL OR after the idle (stale) timeout — a token unused for
        // longer than IDLE_TIMEOUT is cut off even within its 24h cap, shrinking a lost device's window.
        if (SessionExpiryPolicy.isExpired(now, session.expiresAtMs, session.lastUsedMs)) {
            sessions.remove(token)
            persistSessions()
            return null
        }
        sessions[token] = session.copy(lastUsedMs = now)
        if (now - lastUsedPersistAtMs >= LAST_USED_PERSIST_INTERVAL_MS) {
            lastUsedPersistAtMs = now
            persistSessions()
        }
        return session
    }

    /**
     * #298: rotate the caller's bearer token. Validates [currentToken] (which also enforces idle +
     * absolute expiry), then mints a fresh token for the same device with a fresh lifetime and
     * invalidates the old one. Returns the new-token JSON (with rotated=true), or null if the
     * current token is missing/expired/unknown. Lets an active parent roll forward without
     * re-pairing instead of being hard-cut at the 24h cap.
     */
    @Synchronized
    fun rotateToken(currentToken: String?): JSONObject? {
        val session = validate(currentToken) ?: return null
        sessions.remove(session.token)
        return issueToken(session.deviceId, session.tlsAudioRequired).put("rotated", true)
    }

    /**
     * #348: mint an independent durable refresh credential for [deviceId]. Returns the plaintext
     * refresh token ONCE — only its hash is stored. The endpoint that calls this is bearer-gated,
     * so it bootstraps from the parent's CURRENT valid session (no PIN, no physical child access).
     */
    @Synchronized
    fun issueRefreshToken(deviceId: String, tlsAudioRequired: Boolean = false): JSONObject {
        val now = System.currentTimeMillis()
        val token = generateToken()
        val expiresAt = DurablePairingPolicy.PERMANENT_EXPIRY_MS
        val hash = RefreshTokenHasher.hash(token)
        refreshCreds[hash] = RefreshCred(
            deviceId = deviceId,
            tokenHash = hash,
            createdAtMs = now,
            expiresAtMs = expiresAt,
            lastUsedMs = now,
            tlsAudioRequired = tlsAudioRequired
        )
        if (!persistRefreshCreds()) {
            refreshCreds.remove(hash)
            // commit() also updates SharedPreferences memory on failure; restore the previous
            // credential set there so a later reload cannot resurrect this unacknowledged grant.
            persistRefreshCreds()
            throw java.io.IOException("Permanent pairing credential could not be saved")
        }
        return JSONObject()
            .put("refreshToken", token)
            .put("deviceId", deviceId)
            .put("expiresAtMs", expiresAt)
            .put("ttlMs", DurablePairingPolicy.PERMANENT_TTL_MS)
    }

    /**
     * #348: redeem a refresh token for a fresh bearer. Authenticated solely by the refresh token,
     * so it works even after the bearer has fully expired (the whole point — survive months /
     * arbitrary offline gaps). Returns the new-bearer JSON, or null if the device has no
     * credential, it is expired, or the token does not match (constant-time check).
     */
    @Synchronized
    fun redeemRefreshToken(deviceId: String?, refreshToken: String?): JSONObject? {
        if (deviceId.isNullOrBlank() || refreshToken.isNullOrBlank()) return null
        val hash = RefreshTokenHasher.hash(refreshToken)
        val cred = refreshCreds[hash]?.takeIf { it.deviceId == deviceId } ?: return null
        val now = System.currentTimeMillis()
        if (!RefreshTokenHasher.matches(refreshToken, cred.tokenHash)) return null
        refreshCreds[hash] = cred.copy(lastUsedMs = now)
        persistRefreshCreds()
        return issueToken(deviceId, cred.tlsAudioRequired).put("refreshed", true)
    }

    /** Exchange a QR/legacy bearer for this parent's own bearer and permanent credential.
     * The QR bearer remains usable for the next phone. No other parent's secrets are rotated.
     */
    @Synchronized
    fun bootstrapParent(currentToken: String?, parentDeviceId: String): JSONObject? {
        if (!PARENT_ID.matches(parentDeviceId)) return null
        val session = validate(currentToken) ?: return null
        val refresh = issueRefreshToken(parentDeviceId, session.tlsAudioRequired)
        val bearer = issueToken(parentDeviceId, session.tlsAudioRequired)
        return refresh.put("token", bearer.getString("token"))
    }

    /** #348: count of stored durable refresh credentials (for /api/status diagnostics). */
    @Synchronized
    fun refreshCredentialCount(): Int = refreshCreds.size

    @Synchronized
    fun revokeToken(token: String): Boolean {
        val removed = sessions.remove(token) != null
        if (removed) persistSessions()
        return removed
    }

    /** The roster is display metadata; credential ownership is authoritative even if it is lost. */
    @Synchronized
    fun hasCredentialsForDevice(deviceId: String): Boolean =
        sessions.values.any { it.deviceId == deviceId } || refreshCreds.values.any { it.deviceId == deviceId }

    @Synchronized
    fun revokeAllForDevice(deviceId: String): Int {
        val before = sessions.size
        val toRemove = sessions.values.filter { it.deviceId == deviceId }.map { it.token }
        toRemove.forEach { sessions.remove(it) }
        if (toRemove.isNotEmpty()) persistSessions()
        // #348: a revoked/unpaired device must not be able to renew — drop its refresh credential too.
        val revokedRefresh = refreshCreds.values.filter { it.deviceId == deviceId }.map { it.tokenHash }
        revokedRefresh.forEach { refreshCreds.remove(it) }
        if (revokedRefresh.isNotEmpty()) persistRefreshCreds()
        return before - sessions.size
    }

    @Synchronized
    fun revokeAll(): Int {
        val count = sessions.size
        sessions.clear()
        persistSessions()
        // #348: clear durable refresh credentials too (e.g. a panic "log out everyone").
        if (refreshCreds.isNotEmpty()) {
            refreshCreds.clear()
            persistRefreshCreds()
        }
        return count
    }

    /**
     * Authoritative listing used by the metrics endpoint behind auth. Returns
     * 8-char token prefixes for cross-restart correlation in operator tools.
     * NEVER ship this directly to anonymous callers — even the prefix gives
     * an attacker a stable per-session identifier across restarts (#190-HIGH).
     */
    @Synchronized
    fun listSessions(): JSONObject {
        purgeExpired()
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for (s in sessions.values) {
            arr.put(
                JSONObject()
                    .put("deviceId", s.deviceId)
                    .put("createdAtMs", s.createdAtMs)
                    .put("expiresAtMs", s.expiresAtMs)
                    .put("lastUsedMs", s.lastUsedMs)
                    .put("tokenPrefix", s.token.take(8))
                    .put("rotateRecommended", SessionExpiryPolicy.shouldRotate(now, s.createdAtMs)) // #298
                    .put("tlsAudioRequired", s.tlsAudioRequired)
            )
        }
        return JSONObject().put("count", arr.length()).put("sessions", arr)
    }

    /**
     * Listing safe for unauthenticated surfaces (e.g. /metrics if it's ever
     * re-exposed). Strips deviceId and tokenPrefix; keeps only count and
     * coarse timing data that doesn't identify individual parents.
     */
    @Synchronized
    fun listSessionsPublicSafe(): JSONObject {
        purgeExpired()
        return JSONObject()
            .put("count", sessions.size)
            .put("oldestExpiresAtMs", sessions.values.minOfOrNull { it.expiresAtMs } ?: 0L)
            .put("newestExpiresAtMs", sessions.values.maxOfOrNull { it.expiresAtMs } ?: 0L)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    @Synchronized
    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        // #298: drop both absolute-expired and idle-stale sessions.
        val expired = sessions.values
            .filter { SessionExpiryPolicy.isExpired(now, it.expiresAtMs, it.lastUsedMs) }
            .map { it.token }
        if (expired.isNotEmpty()) {
            expired.forEach { sessions.remove(it) }
            persistSessions()
        }
    }

    private fun loadSessions() {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return
        try {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val token = o.optString("token")
                val deviceId = o.optString("deviceId")
                val expires = o.optLong("expiresAtMs")
                val lastUsed = o.optLong("lastUsedMs", now)
                // #298: don't resurrect absolute-expired or idle-stale sessions from disk.
                if (token.isBlank() || deviceId.isBlank() ||
                    SessionExpiryPolicy.isExpired(now, expires, lastUsed)) continue
                sessions[token] = Session(
                    token = token,
                    deviceId = deviceId,
                    createdAtMs = o.optLong("createdAtMs", now),
                    expiresAtMs = expires,
                    lastUsedMs = lastUsed,
                    tlsAudioRequired = o.optBoolean("tlsAudioRequired", false)
                )
            }
        } catch (_: Exception) {
            // Corrupt store: drop it.
            prefs.edit().remove(KEY_SESSIONS).apply()
        }
    }

    private fun persistSessions() {
        val arr = JSONArray()
        for (s in sessions.values) {
            arr.put(
                JSONObject()
                    .put("token", s.token)
                    .put("deviceId", s.deviceId)
                    .put("createdAtMs", s.createdAtMs)
                    .put("expiresAtMs", s.expiresAtMs)
                    .put("lastUsedMs", s.lastUsedMs)
                    .put("tlsAudioRequired", s.tlsAudioRequired)
            )
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    private fun loadRefreshCreds() {
        val raw = prefs.getString(KEY_REFRESH, null) ?: return
        try {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            var migratedLifetime = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val deviceId = o.optString("deviceId")
                val hash = o.optString("tokenHash")
                if (deviceId.isBlank() || hash.isBlank()) continue
                // Pair-once migration: an old one-year expiry was not an explicit revoke.
                // Preserve its hashed secret and make it permanent. Explicitly revoked
                // credentials are absent from the store and therefore stay revoked.
                val storedExpires = o.optLong("expiresAtMs")
                val expires = DurablePairingPolicy.normalizedExpiry(storedExpires)
                if (storedExpires != expires) migratedLifetime = true
                refreshCreds[hash] = RefreshCred(
                    deviceId = deviceId,
                    tokenHash = hash,
                    createdAtMs = o.optLong("createdAtMs", now),
                    expiresAtMs = expires,
                    lastUsedMs = o.optLong("lastUsedMs", now),
                    tlsAudioRequired = o.optBoolean("tlsAudioRequired", false)
                )
            }
            if (migratedLifetime) persistRefreshCreds()
        } catch (_: Exception) {
            prefs.edit().remove(KEY_REFRESH).apply()
        }
    }

    private fun persistRefreshCreds(): Boolean {
        val arr = JSONArray()
        for (c in refreshCreds.values) {
            arr.put(
                JSONObject()
                    .put("deviceId", c.deviceId)
                    .put("tokenHash", c.tokenHash)
                    .put("createdAtMs", c.createdAtMs)
                    .put("expiresAtMs", c.expiresAtMs)
                    .put("lastUsedMs", c.lastUsedMs)
                    .put("tlsAudioRequired", c.tlsAudioRequired)
            )
        }
        // A parent must never receive its only permanent secret before the child saves its hash.
        // Bearer persistence is asynchronous because an existing permanent proof can recover it.
        return prefs.edit().putString(KEY_REFRESH, arr.toString()).commit()
    }

    /**
     * Reload sessions from disk. Safe to call from any thread. Used by the
     * process-wide singleton path so that a SessionAuthority instance created in
     * one component (e.g. ChildPairingActivity) sees tokens minted in another
     * (e.g. MonitorService) without process restart.
     *
     * Today we use [getInstance] which returns a single shared instance, so this
     * is just defence-in-depth for the rare case where direct construction is
     * unavoidable (tests, multi-process surfaces).
     */
    @Synchronized
    fun reload() {
        sessions.clear()
        loadSessions()
        refreshCreds.clear()
        loadRefreshCreds()
    }

    companion object {
        private val PARENT_ID = Regex("^parent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private const val PREFS_NAME = "SessionAuthorityPrefs"
        private const val KEY_SESSIONS = "sessions_v1"
        // #348: durable refresh credentials, stored separately (hashes only).
        private const val KEY_REFRESH = "refresh_creds_v1"
        // #298: single source of truth for the absolute lifetime (see SessionExpiryPolicy).
        private const val SESSION_TTL_MS = SessionExpiryPolicy.ABSOLUTE_TTL_MS
        // #298: throttle for persisting lastUsedMs on the validate() hot path (coalesced).
        private const val LAST_USED_PERSIST_INTERVAL_MS = 10L * 60L * 1000L
        private const val TOKEN_BYTES = 32

        @Volatile private var INSTANCE: SessionAuthority? = null

        /**
         * Process-wide singleton. Both [MonitorService] (token validator) and
         * [ChildPairingActivity] (token issuer at QR-generation time) must
         * share the same in-memory map of sessions; otherwise a token minted in
         * the activity would not be recognised by the service running in the
         * same process. SharedPreferences persistence is the cross-process
         * fallback but is not relied on for the common single-process case.
         */
        fun getInstance(context: Context): SessionAuthority {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionAuthority(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
