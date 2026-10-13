package de.rochefort.childmonitor.tracker

import android.content.Context
import android.util.Log
import de.rochefort.childmonitor.LogManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists the list of child profiles in SharedPreferences as JSON.
 * Small, read-mostly, no encryption needed beyond app-private storage.
 */
class ChildProfileStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<ChildProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    ChildProfile.fromJson(o)?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            // CRITICAL (#187): silently returning emptyList on corrupt JSON used to make
            // child profiles "vanish", at which point any new profile got a fresh UUID and
            // the existing medication dose caps (which key off the old UUID) became
            // detached — dose caps were effectively bypassable. Now we:
            //   1. Loud-log the corruption with the raw payload preserved.
            //   2. Quarantine the bad blob to KEY_PROFILES_CORRUPT so a future migration
            //      tool can attempt recovery (we don't auto-recover here — recovery should
            //      be a deliberate, user-visible action, not a silent rewrite).
            //   3. Still return emptyList so the rest of the app keeps functioning, but the
            //      caller can now observe quarantine state via hasQuarantinedProfiles().
            Log.e(TAG, "ChildProfiles JSON corrupt — quarantining and returning empty", e)
            LogManager.e(
                LogManager.LogCategory.SYSTEM,
                "ChildProfileStore: corrupt JSON detected (size=${raw.length} chars). " +
                    "Quarantined to $KEY_PROFILES_CORRUPT — UI must prompt the user before " +
                    "creating new profiles, otherwise dose caps may be re-keyed.",
                e
            )
            prefs.edit()
                .putString(KEY_PROFILES_CORRUPT, raw)
                .remove(KEY_PROFILES)
                .apply()
            emptyList()
        }
    }

    /**
     * True if the last list() call quarantined a corrupt profiles blob. UI should
     * prompt the user before creating a new profile (which would re-key all dose caps
     * to a fresh UUID).
     */
    fun hasQuarantinedProfiles(): Boolean = prefs.contains(KEY_PROFILES_CORRUPT)

    /** Read the raw quarantined JSON (for diagnostic export). null if none. */
    fun quarantinedProfilesRaw(): String? = prefs.getString(KEY_PROFILES_CORRUPT, null)

    /** Discard the quarantined blob — call after the user has acknowledged and/or recovered. */
    fun clearQuarantinedProfiles() {
        prefs.edit().remove(KEY_PROFILES_CORRUPT).apply()
    }

    @Synchronized
    fun upsert(profile: ChildProfile): ChildProfile {
        val current = list().toMutableList()
        val existingIdx = current.indexOfFirst { it.id == profile.id }
        if (existingIdx >= 0) current[existingIdx] = profile else current.add(profile)
        save(current)
        return profile
    }

    @Synchronized
    fun remove(id: String) {
        val current = list().filter { it.id != id }
        save(current)
        if (getActiveId() == id) setActiveId(current.firstOrNull()?.id)
    }

    @Synchronized
    fun getActiveId(): String? = prefs.getString(KEY_ACTIVE, null)

    @Synchronized
    fun setActiveId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    fun activeProfile(): ChildProfile? {
        val id = getActiveId()
        val all = list()
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    private fun save(profiles: List<ChildProfile>) {
        val arr = JSONArray()
        for (p in profiles) arr.put(p.toJson())
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "ChildProfileStore"
        private const val PREFS = "ChildProfiles"
        private const val KEY_PROFILES = "profiles_v1"
        private const val KEY_PROFILES_CORRUPT = "profiles_v1_corrupt_quarantine"
        private const val KEY_ACTIVE = "active_id"

        // #192-2: process-wide singleton. Previously 11 callsites each did
        // `new ChildProfileStore(ctx)` — @Synchronized methods lock the
        // INSTANCE not the class, so a concurrent upsert + delete across two
        // distinct instances could resurrect a deleted profile and orphan
        // tracker_events keyed off the now-deleted profile.id. Mirrors the
        // TrackerStore.get(context) singleton pattern.
        @Volatile private var INSTANCE: ChildProfileStore? = null

        fun get(context: Context): ChildProfileStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChildProfileStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Test-only: drop the cached singleton so the next [get] call constructs
         * a fresh instance against a (possibly different) Context. Mirrors
         * TrackerStore.resetForTest. Used by per-test isolation in
         * androidTest.
         */
        @JvmStatic
        internal fun resetForTest() {
            synchronized(this) { INSTANCE = null }
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}
