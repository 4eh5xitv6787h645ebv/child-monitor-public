package de.rochefort.childmonitor

import android.content.Context
import de.rochefort.childmonitor.security.PairingChallengeGate
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * Manages local trust relationships for parent devices.
 * This is intentionally local-only and persists data on-device.
 */
class PairingManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    // [#232] Challenge verification + the brute-force attempt cap live in this pure,
    // unit-tested gate. A wrong PIN now counts against the active challenge and the
    // challenge is burned after MAX_PAIRING_ATTEMPTS, so the 6-digit code can no longer be
    // brute-forced within the TTL window — regardless of how a caller spoofs deviceId/IP
    // to dodge the per-key network rate limiter.
    private val challengeGate = PairingChallengeGate(maxAttempts = MAX_PAIRING_ATTEMPTS)

    data class PairedDevice(
        val deviceId: String,
        val deviceName: String,
        val pairedAtMs: Long,
        val lastSeenMs: Long
    )

    @Synchronized
    fun createChallenge(childHost: String, webPort: Int): JSONObject {
        val now = System.currentTimeMillis()
        val challengeId = UUID.randomUUID().toString()
        val code = sixDigitCode()
        val expiresAtMs = now + CHALLENGE_TTL_MS
        challengeGate.start(code, expiresAtMs)

        val qrPayload = "childmonitor://pair?host=$childHost&port=$webPort&challenge=$challengeId&code=$code"
        return JSONObject()
            .put("challengeId", challengeId)
            .put("pin", code)
            .put("expiresAtMs", expiresAtMs)
            .put("ttlMs", CHALLENGE_TTL_MS)
            .put("qrPayload", qrPayload)
    }

    @Synchronized
    fun completePairing(code: String, deviceId: String, deviceName: String?): JSONObject {
        // Reject malformed requests before consuming a challenge attempt.
        if (deviceId.isBlank()) {
            return JSONObject().put("success", false).put("error", "deviceId is required")
        }
        when (challengeGate.verify(code)) {
            PairingChallengeGate.Verdict.NO_CHALLENGE ->
                return JSONObject().put("success", false).put("error", "No active challenge")
            PairingChallengeGate.Verdict.EXPIRED ->
                return JSONObject().put("success", false).put("error", "Challenge expired")
            PairingChallengeGate.Verdict.WRONG_CODE ->
                return JSONObject().put("success", false).put("error", "Invalid pairing code")
            PairingChallengeGate.Verdict.OK -> { /* fall through to persist the device */ }
        }

        return registerAuthenticatedDevice(deviceId, deviceName)
    }

    /** Caller must already hold a validated pairing bearer (QR bootstrap). */
    @Synchronized
    fun registerAuthenticatedDevice(deviceId: String, deviceName: String?): JSONObject {
        val now = System.currentTimeMillis()
        val sanitizedName = deviceName?.trim().takeUnless { it.isNullOrEmpty() } ?: "Parent device"
        val devices = loadDevicesMutable()
        val existing = devices.indexOfFirst { it.deviceId == deviceId }
        val updated = PairedDevice(
            deviceId = deviceId,
            deviceName = sanitizedName,
            pairedAtMs = if (existing >= 0) devices[existing].pairedAtMs else now,
            lastSeenMs = now
        )
        if (existing >= 0) {
            devices[existing] = updated
        } else {
            devices.add(updated)
        }
        saveDevices(devices)

        return JSONObject()
            .put("success", true)
            .put("deviceId", updated.deviceId)
            .put("deviceName", updated.deviceName)
            .put("pairedAtMs", updated.pairedAtMs)
    }

    @Synchronized
    fun revokeDevice(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        val devices = loadDevicesMutable()
        val removed = devices.removeAll { it.deviceId == deviceId }
        if (removed) {
            saveDevices(devices)
        }
        return removed
    }

    @Synchronized
    fun devicesAsJson(): JSONObject {
        val arr = JSONArray()
        for (device in loadDevicesMutable()) {
            arr.put(
                JSONObject()
                    .put("deviceId", device.deviceId)
                    .put("deviceName", device.deviceName)
                    .put("pairedAtMs", device.pairedAtMs)
                    .put("lastSeenMs", device.lastSeenMs)
            )
        }
        return JSONObject()
            .put("count", arr.length())
            .put("devices", arr)
    }

    @Synchronized
    fun pairedDeviceCount(): Int = loadDevicesMutable().size

    fun hasActiveChallenge(): Boolean = challengeGate.hasActive()

    private fun sixDigitCode(): String {
        val number = random.nextInt(1_000_000)
        return WireFormat.pairingCode(number)
    }

    private fun loadDevicesMutable(): MutableList<PairedDevice> {
        val raw = prefs.getString(KEY_DEVICES_JSON, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<PairedDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("deviceId")
                if (id.isBlank()) continue
                out.add(
                    PairedDevice(
                        deviceId = id,
                        deviceName = o.optString("deviceName", "Parent device"),
                        pairedAtMs = o.optLong("pairedAtMs", System.currentTimeMillis()),
                        lastSeenMs = o.optLong("lastSeenMs", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveDevices(devices: List<PairedDevice>) {
        val arr = JSONArray()
        for (device in devices) {
            arr.put(
                JSONObject()
                    .put("deviceId", device.deviceId)
                    .put("deviceName", device.deviceName)
                    .put("pairedAtMs", device.pairedAtMs)
                    .put("lastSeenMs", device.lastSeenMs)
            )
        }
        prefs.edit().putString(KEY_DEVICES_JSON, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "PairingPreferences"
        private const val KEY_DEVICES_JSON = "trusted_devices_v1"
        private const val CHALLENGE_TTL_MS = 2 * 60 * 1000L
        private const val MAX_PAIRING_ATTEMPTS = 5
    }
}
