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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the parent device's record of child devices it has paired with.
 * Identifies a child by its stable `deviceId` (from a QR code or NSD TXT record),
 * not by IP — so when the child's IP changes (new WiFi, DHCP lease), we can still
 * reconnect by looking up the deviceId via mDNS.
 *
 * `lastKnownIp` + `lastKnownPort` are tried first as a fast path; if the connect
 * fails, the reconnect loop falls back to NSD discovery filtered by deviceId.
 *
 * `userName` is the PARENT-assigned name for the "Kids" feature (e.g. "kid1") — distinct
 * from [name], which is the CHILD-advertised friendly name (overwritten on every reconnect).
 * Giving a child a [userName] makes it a PERMANENT link ([isPermanent]): it is never evicted by
 * the recency cap and reconnect upserts preserve the name. See [PairedChildRoster].
 * `createdAtMs` records when the child was first added (0 = unknown, e.g. legacy records).
 */
data class PairedChild(
    val deviceId: String,
    val name: String,
    val lastKnownIp: String,
    val lastKnownPort: Int,
    val lastSeenMs: Long,
    val userName: String? = null,
    val createdAtMs: Long = 0L
) {
    /** The name to show the user: their assigned name first, then the child-advertised name, then the id. */
    val displayName: String
        get() = userName?.takeIf { it.isNotBlank() }
            ?: name.takeIf { it.isNotBlank() }
            ?: deviceId

    /** A permanent link — exempt from the recency cap. True once the user has named the child. */
    val isPermanent: Boolean
        get() = !userName.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("name", name)
        .put("ip", lastKnownIp)
        .put("port", lastKnownPort)
        .put("lastSeenMs", lastSeenMs)
        // Only persist a user name when set, so legacy/back-compat reads stay clean (org.json
        // drops a null value anyway, but being explicit avoids a stray "null" key).
        .apply { if (!userName.isNullOrBlank()) put("userName", userName) }
        .put("createdAtMs", createdAtMs)

    companion object {
        fun fromJson(o: JSONObject): PairedChild? {
            return try {
                val id = o.optString("deviceId")
                if (id.isBlank()) return null
                // #310/#423: this loads PERSISTED/RESTORED pairings, and a backup file can be
                // attacker-crafted. lastKnownIp is interpolated verbatim into the raw talkback WS
                // upgrade ("Host: $address:$wsPort\r\n"), so a CRLF/injection host must never survive
                // here. Drop an unsafe ip (the reconnect loop just falls back to NSD discovery by
                // deviceId) rather than the whole record, so a corrupt ip doesn't lose the pairing.
                val rawIp = o.optString("ip", "")
                val safeIp = if (rawIp.isBlank() || PairingValidation.isSafeHost(rawIp)) rawIp else ""
                val rawPort = o.optInt("port", 10000)
                val safePort = if (PairingValidation.isValidPort(rawPort)) rawPort else 10000
                PairedChild(
                    deviceId = id,
                    name = o.optString("name", id),
                    lastKnownIp = safeIp,
                    lastKnownPort = safePort,
                    lastSeenMs = o.optLong("lastSeenMs", System.currentTimeMillis()),
                    // Back-compat: records written before the Kids feature lack these keys.
                    userName = o.optString("userName", "").takeIf { it.isNotBlank() },
                    createdAtMs = o.optLong("createdAtMs", 0L)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object PairedChildrenStore {
    private const val PREFS = "ListenPreferences"
    private const val KEY = "pairedChildren_v1" // gitleaks:allow -- preference key, not a credential
    // #402 Kids: the parent's chosen default kid — Quick-Reconnect / boot auto-start launches it
    // BY deviceId. A stale value (kid removed) is harmless: the resolver falls back to most-recent.
    private const val KEY_DEFAULT = "defaultKid_v1" // gitleaks:allow -- preference key, not a credential

    fun list(context: Context): List<PairedChild> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(PairedChild::fromJson)
            }
                // Defensive dedupe in case a corrupted blob ever holds two entries for one device;
                // keep the first (most-recent) so downstream lookups/caps stay unambiguous.
                .distinctBy { it.deviceId }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Move-to-front upsert (matched by deviceId), merge-preserving any user-assigned name /
     * pin from the existing entry (so a reconnect never clobbers a named kid) and capping
     * non-permanent entries to [PairedChildRoster.MAX_RECENT]. See [PairedChildRoster.upserted].
     */
    fun upsert(context: Context, child: PairedChild) {
        save(context, PairedChildRoster.upserted(list(context), child, System.currentTimeMillis()))
    }

    /** Set/clear the parent-assigned name for a child (Kids feature). A named child is permanent. */
    fun setUserName(context: Context, deviceId: String, userName: String?) {
        save(context, PairedChildRoster.withUserName(list(context), deviceId, userName))
    }

    fun remove(context: Context, deviceId: String) {
        save(context, PairedChildRoster.removed(list(context), deviceId))
        // Clear the default pointer if it pointed at the removed kid (keep prefs tidy).
        if (getDefaultKidDeviceId(context) == deviceId) setDefaultKidDeviceId(context, null)
    }

    fun findByDeviceId(context: Context, deviceId: String): PairedChild? =
        list(context).firstOrNull { it.deviceId == deviceId }

    /** The parent's chosen default kid's deviceId, or null if none set. */
    fun getDefaultKidDeviceId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)?.takeIf { it.isNotBlank() }

    /** Set (or clear, with null/blank) the default kid. */
    fun setDefaultKidDeviceId(context: Context, deviceId: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (deviceId.isNullOrBlank()) edit.remove(KEY_DEFAULT) else edit.putString(KEY_DEFAULT, deviceId)
        edit.apply()
    }

    private fun save(context: Context, items: List<PairedChild>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
