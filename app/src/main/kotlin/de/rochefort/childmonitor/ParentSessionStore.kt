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
package de.rochefort.childmonitor

import android.content.Context
import android.content.SharedPreferences
import de.rochefort.childmonitor.security.ParentCredentialCrypto
import de.rochefort.childmonitor.security.ParentCredentialKind

/**
 * Parent-side bearer-token + TLS-pin store. Maps childDeviceId → bearer token,
 * pinned SHA-256 fingerprint, and HTTPS port. The token is issued by the child's
 * [security.SessionAuthority] at QR-pairing time and presented on every HTTP /
 * WebSocket request (#193). The fingerprint pin and HTTPS port are recorded at
 * the same pairing event and consulted by the parent's HTTP / WS code to
 * trust-on-first-use (TOFU) pin the child's TLS cert (#129).
 *
 * A rejected bearer is renewed using the permanent refresh credential. Only an explicit
 * revoke or loss of the protected credential requires another QR scan. The pin is preserved across
 * forget() so the parent stays bound to that physical child device even when
 * the session expires.
 */
object ParentSessionStore {

    enum class CredentialFailure {
        /** Keystore/storage could not protect a newly received credential. Retry after restart. */
        SECURE_STORAGE_UNAVAILABLE,
        /** Existing ciphertext is corrupt or its non-exportable key was lost/invalidated. */
        REPAIR_REQUIRED,
    }

    private const val PREFS = "ParentSession"
    private const val KEY_PREFIX_TOKEN = "token_v1::"
    private const val KEY_PREFIX_TOKEN_ENCRYPTED = "token_enc_v2::"
    private const val KEY_PREFIX_FP = "fp_v1::"
    private const val KEY_PREFIX_HTTPS_PORT = "https_port_v1::"
    private const val KEY_PREFIX_AUDIO_TLS_PORT = "audio_tls_port_v1::"
    private const val KEY_PREFIX_EVER_AUDIO_TLS = "ever_audio_tls_v1::"
    private const val KEY_PREFIX_EVER_PINNED = "ever_pinned_v1::"
    // #348 part 2: durable refresh credential per child — lets the parent renew short-lived bearers
    // for months without re-pairing. Plus its mint time + TTL for the proactive re-mint decision.
    private const val KEY_PREFIX_REFRESH = "refresh_v1::"
    private const val KEY_PREFIX_REFRESH_ENCRYPTED = "refresh_enc_v2::"
    private const val KEY_PREFIX_CREDENTIAL_ERROR = "credential_error_v1::"
    private const val KEY_PREFIX_REFRESH_DEVICE = "refresh_device_v2::"
    private const val KEY_PREFIX_REFRESH_AT = "refresh_at_v1::"
    private const val KEY_PREFIX_REFRESH_TTL = "refresh_ttl_v1::"

    @Synchronized
    fun put(context: Context, childDeviceId: String, token: String): Boolean =
        storeSecret(context, childDeviceId, SecretKind.BEARER, token)

    /**
     * Record the TLS fingerprint + HTTPS port the child advertised at QR-pairing
     * time. Once set, [fingerprintFor] returns the pinned value and the parent's
     * HTTPS code refuses any TLS handshake whose leaf cert doesn't match.
     *
     * Re-pairing (rescanning a QR for the same childDeviceId) overwrites the pin
     * — this is intentional: it's how the user explicitly authorises a cert
     * rotation (e.g. they wiped the child app and re-installed it). A silent
     * change to the cert WITHOUT a rescan correctly triggers the pin-mismatch
     * branch on the next snapshot fetch.
     */
    fun putTlsPin(
        context: Context,
        childDeviceId: String,
        fingerprint: String?,
        httpsPort: Int?,
        audioTlsPort: Int? = null
    ) {
        if (childDeviceId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (fingerprint.isNullOrBlank()) {
            prefs.remove(KEY_PREFIX_FP + childDeviceId)
            prefs.remove(KEY_PREFIX_HTTPS_PORT + childDeviceId)
            prefs.remove(KEY_PREFIX_AUDIO_TLS_PORT + childDeviceId)
        } else {
            prefs.putString(KEY_PREFIX_FP + childDeviceId, fingerprint)
            // #203: latch "this device has presented a TLS fingerprint" forever.
            // Never removed — even when the current pin is later cleared — so the
            // transport policy can refuse a silent cleartext downgrade for a
            // device that was once secured.
            prefs.putBoolean(KEY_PREFIX_EVER_PINNED + childDeviceId, true)
            if (httpsPort != null && httpsPort in 1..65535) {
                prefs.putInt(KEY_PREFIX_HTTPS_PORT + childDeviceId, httpsPort)
            } else {
                prefs.remove(KEY_PREFIX_HTTPS_PORT + childDeviceId)
            }
            if (audioTlsPort != null && audioTlsPort in 1..65535) {
                prefs.putInt(KEY_PREFIX_AUDIO_TLS_PORT + childDeviceId, audioTlsPort)
                // Unlike hasEverPinned (which predates audio TLS), this latch is
                // set only after a QR explicitly advertised the secure audio
                // endpoint. Losing the current port must then fail closed.
                prefs.putBoolean(KEY_PREFIX_EVER_AUDIO_TLS + childDeviceId, true)
            } else {
                prefs.remove(KEY_PREFIX_AUDIO_TLS_PORT + childDeviceId)
            }
        }
        prefs.apply()
    }

    @Synchronized
    fun get(context: Context, childDeviceId: String?): String? {
        if (childDeviceId.isNullOrBlank()) return null
        return readSecret(context, childDeviceId, SecretKind.BEARER)
    }

    fun fingerprintFor(context: Context, childDeviceId: String?): String? {
        if (childDeviceId.isNullOrBlank()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX_FP + childDeviceId, null)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Whether [childDeviceId] has ever presented a TLS fingerprint (#203). Once
     * true it stays true, so [TransportPolicy] can refuse to downgrade a
     * previously-secured device back to cleartext.
     */
    fun hasEverPinned(context: Context, childDeviceId: String?): Boolean {
        if (childDeviceId.isNullOrBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX_EVER_PINNED + childDeviceId, false)
    }

    fun httpsPortFor(context: Context, childDeviceId: String?): Int? {
        if (childDeviceId.isNullOrBlank()) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX_HTTPS_PORT + childDeviceId, -1)
        return raw.takeIf { it in 1..65535 }
    }

    fun audioTlsPortFor(context: Context, childDeviceId: String?): Int? {
        if (childDeviceId.isNullOrBlank()) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX_AUDIO_TLS_PORT + childDeviceId, -1)
        return raw.takeIf { it in 1..65535 }
    }

    fun hasEverAudioTls(context: Context, childDeviceId: String?): Boolean {
        if (childDeviceId.isNullOrBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX_EVER_AUDIO_TLS + childDeviceId, false)
    }

    /** Drop the token for [childDeviceId] — typically after a 401. Keeps the TLS
     *  pin and the [hasEverPinned] latch (#203) so a token expiry never downgrades
     *  a previously-secured device to cleartext. Also keeps the refresh credential
     *  (#348) — the whole point is that a bearer expiry is recoverable WITHOUT re-pairing. */
    @Synchronized
    fun forget(context: Context, childDeviceId: String?) {
        if (childDeviceId.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX_TOKEN + childDeviceId)
            .remove(KEY_PREFIX_TOKEN_ENCRYPTED + childDeviceId)
            .remove(errorKey(SecretKind.BEARER, childDeviceId))
            .commit()
    }

    /** Drop BOTH the bearer token and the durable refresh credential (#348) for [childDeviceId] —
     *  used when the user removes a child from the "Kids" roster, so the app stops futile background
     *  renewals for a device it no longer tracks. Deliberately KEEPS the TLS pin and the
     *  [hasEverPinned] latch (#203): if the same child is ever re-paired it stays bound to that
     *  physical device and can never be silently downgraded to cleartext. */
    @Synchronized
    fun forgetAll(context: Context, childDeviceId: String?) {
        if (childDeviceId.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX_TOKEN + childDeviceId)
            .remove(KEY_PREFIX_TOKEN_ENCRYPTED + childDeviceId)
            .remove(KEY_PREFIX_REFRESH + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_ENCRYPTED + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_DEVICE + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_AT + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_TTL + childDeviceId)
            .remove(errorKey(SecretKind.BEARER, childDeviceId))
            .remove(errorKey(SecretKind.REFRESH, childDeviceId))
            .commit()
    }

    /** #354: drop the durable refresh credential for [childDeviceId] — call ONLY on a definitive 401
     *  (revoked/invalid) from /api/session/refresh, NOT on a transient 429 rate-limit. After this the
     *  device can no longer self-renew a bearer and must be re-paired. Keeps the TLS pin. */
    @Synchronized
    fun forgetRefresh(context: Context, childDeviceId: String?) {
        if (childDeviceId.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX_REFRESH + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_ENCRYPTED + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_DEVICE + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_AT + childDeviceId)
            .remove(KEY_PREFIX_REFRESH_TTL + childDeviceId)
            .putString(
                errorKey(SecretKind.REFRESH, childDeviceId),
                CredentialFailure.REPAIR_REQUIRED.name,
            )
            .commit()
    }

    /** Save a renewal only if it still belongs to the pairing that made the request. */
    @Synchronized
    fun putRenewedBearer(
        context: Context, childDeviceId: String, token: String,
        expectedRefresh: String, expectedBearer: String?,
    ): Boolean {
        if (getRefreshToken(context, childDeviceId) != expectedRefresh ||
            get(context, childDeviceId) != expectedBearer) return false
        return put(context, childDeviceId, token)
    }

    /** A late rejection must never erase credentials saved by a concurrent renewal/re-pair. */
    @Synchronized
    fun forgetRejectedRefresh(
        context: Context,
        childDeviceId: String,
        rejectedRefresh: String,
        rejectedBearer: String?,
    ): Boolean {
        if (getRefreshToken(context, childDeviceId) != rejectedRefresh) return false
        forgetRefresh(context, childDeviceId)
        if (rejectedBearer != null && get(context, childDeviceId) == rejectedBearer) {
            forget(context, childDeviceId)
        }
        return true
    }

    // ---- #348 part 2: durable refresh credential ----

    /** Store the refresh credential for [childDeviceId] with its mint time + TTL. */
    @Synchronized
    fun putRefreshToken(
        context: Context,
        childDeviceId: String,
        token: String,
        issuedAtMs: Long,
        ttlMs: Long,
        refreshDeviceId: String = childDeviceId,
        bearerToken: String? = null,
    ): Boolean {
        if (childDeviceId.isBlank() || token.isBlank()) return false
        val encrypted = encryptOrRecordFailure(context, childDeviceId, SecretKind.REFRESH, token)
            ?: return false
        val encryptedBearer = if (bearerToken != null) {
            encryptOrRecordFailure(context, childDeviceId, SecretKind.BEARER, bearerToken) ?: return false
        } else null
        val editor = prefs(context).edit()
            .putString(KEY_PREFIX_REFRESH_ENCRYPTED + childDeviceId, encrypted)
            .remove(KEY_PREFIX_REFRESH + childDeviceId)
            .putString(KEY_PREFIX_REFRESH_DEVICE + childDeviceId, refreshDeviceId)
            .putLong(KEY_PREFIX_REFRESH_AT + childDeviceId, issuedAtMs)
            .putLong(KEY_PREFIX_REFRESH_TTL + childDeviceId, ttlMs)
            .remove(errorKey(SecretKind.REFRESH, childDeviceId))
        if (encryptedBearer != null) {
            editor.putString(KEY_PREFIX_TOKEN_ENCRYPTED + childDeviceId, encryptedBearer)
                .remove(KEY_PREFIX_TOKEN + childDeviceId)
                .remove(errorKey(SecretKind.BEARER, childDeviceId))
        }
        val stored = editor.commit()
        if (!stored) recordFailure(context, childDeviceId, SecretKind.REFRESH,
            CredentialFailure.SECURE_STORAGE_UNAVAILABLE)
        return stored
    }

    @Synchronized
    fun getRefreshToken(context: Context, childDeviceId: String?): String? {
        if (childDeviceId.isNullOrBlank()) return null
        return readSecret(context, childDeviceId, SecretKind.REFRESH)
    }

    fun hasRefreshDeviceId(context: Context, childDeviceId: String): Boolean =
        prefs(context).contains(KEY_PREFIX_REFRESH_DEVICE + childDeviceId)

    /** Legacy refresh credentials were scoped to the child ID; keep that fallback on upgrade. */
    fun refreshDeviceId(context: Context, childDeviceId: String): String =
        prefs(context).getString(KEY_PREFIX_REFRESH_DEVICE + childDeviceId, null)
            ?.takeIf { it.isNotBlank() } ?: childDeviceId

    fun refreshIssuedAt(context: Context, childDeviceId: String?): Long {
        if (childDeviceId.isNullOrBlank()) return 0L
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PREFIX_REFRESH_AT + childDeviceId, 0L)
    }

    fun refreshTtlMs(context: Context, childDeviceId: String?): Long {
        if (childDeviceId.isNullOrBlank()) return 0L
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PREFIX_REFRESH_TTL + childDeviceId, 0L)
    }

    /** Non-secret state used by the Kids UI to explain why a connection needs attention. */
    fun credentialFailureFor(context: Context, childDeviceId: String?): CredentialFailure? {
        if (childDeviceId.isNullOrBlank()) return null
        val values = SecretKind.values().mapNotNull { kind ->
            prefs(context).getString(errorKey(kind, childDeviceId), null)
                ?.let { runCatching { CredentialFailure.valueOf(it) }.getOrNull() }
        }
        return when {
            CredentialFailure.REPAIR_REQUIRED in values -> CredentialFailure.REPAIR_REQUIRED
            CredentialFailure.SECURE_STORAGE_UNAVAILABLE in values ->
                CredentialFailure.SECURE_STORAGE_UNAVAILABLE
            else -> null
        }
    }

    private enum class SecretKind(
        val legacyPrefix: String,
        val encryptedPrefix: String,
        val cryptoKind: ParentCredentialKind,
    ) {
        BEARER(KEY_PREFIX_TOKEN, KEY_PREFIX_TOKEN_ENCRYPTED, ParentCredentialKind.BEARER),
        REFRESH(KEY_PREFIX_REFRESH, KEY_PREFIX_REFRESH_ENCRYPTED, ParentCredentialKind.REFRESH),
    }

    private fun storeSecret(
        context: Context,
        childDeviceId: String,
        kind: SecretKind,
        plaintext: String,
    ): Boolean {
        if (childDeviceId.isBlank() || plaintext.isBlank()) return false
        val encrypted = encryptOrRecordFailure(context, childDeviceId, kind, plaintext) ?: return false
        val stored = prefs(context).edit()
            .putString(kind.encryptedPrefix + childDeviceId, encrypted)
            .remove(kind.legacyPrefix + childDeviceId)
            .remove(errorKey(kind, childDeviceId))
            .commit()
        if (!stored) recordFailure(context, childDeviceId, kind,
            CredentialFailure.SECURE_STORAGE_UNAVAILABLE)
        return stored
    }

    private fun readSecret(context: Context, childDeviceId: String, kind: SecretKind): String? {
        val prefs = prefs(context)
        val encryptedKey = kind.encryptedPrefix + childDeviceId
        val encrypted = prefs.getString(encryptedKey, null)?.takeIf { it.isNotBlank() }
        if (encrypted != null) {
            return try {
                ParentCredentialCrypto.decrypt(childDeviceId, kind.cryptoKind, encrypted).also {
                    prefs.edit().remove(errorKey(kind, childDeviceId)).apply()
                }
            } catch (_: Exception) {
                // Never use a coincidentally retained legacy value after authenticated ciphertext
                // fails. That would turn corruption/key invalidation into a plaintext downgrade.
                recordFailure(context, childDeviceId, kind, CredentialFailure.REPAIR_REQUIRED)
                null
            }
        }

        // One-time migration. Encryption happens before the single SharedPreferences transaction;
        // put(ciphertext)+remove(plaintext) therefore become visible together. If disk commit fails,
        // retain and return the still-valid legacy value so an ordinary upgrade does not lock out a
        // pairing; the next read retries migration.
        val legacyKey = kind.legacyPrefix + childDeviceId
        val legacy = prefs.getString(legacyKey, null)?.takeIf { it.isNotBlank() } ?: return null
        val migrated = encryptOrRecordFailure(context, childDeviceId, kind, legacy) ?: return null
        val committed = prefs.edit()
            .putString(encryptedKey, migrated)
            .remove(legacyKey)
            .remove(errorKey(kind, childDeviceId))
            .commit()
        return if (committed) legacy else legacy.also {
            recordFailure(context, childDeviceId, kind, CredentialFailure.SECURE_STORAGE_UNAVAILABLE)
        }
    }

    private fun encryptOrRecordFailure(
        context: Context,
        childDeviceId: String,
        kind: SecretKind,
        plaintext: String,
    ): String? = try {
        ParentCredentialCrypto.encrypt(context, childDeviceId, kind.cryptoKind, plaintext)
    } catch (_: Exception) {
        recordFailure(context, childDeviceId, kind, CredentialFailure.SECURE_STORAGE_UNAVAILABLE)
        null
    }

    private fun recordFailure(
        context: Context,
        childDeviceId: String,
        kind: SecretKind,
        failure: CredentialFailure,
    ) {
        // Persist only a fixed enum name: exception text and credential material never reach logs,
        // crash reports, diagnostic exports, or preference error state.
        prefs(context).edit().putString(errorKey(kind, childDeviceId), failure.name).apply()
    }

    private fun errorKey(kind: SecretKind, childDeviceId: String): String =
        "$KEY_PREFIX_CREDENTIAL_ERROR${kind.name.lowercase()}::$childDeviceId"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
