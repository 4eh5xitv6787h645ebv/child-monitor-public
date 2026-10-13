package de.rochefort.childmonitor.tracker

import android.content.Context

/**
 * Persists the parent's "my clinician cleared this" override for the NIAID
 * high-risk pre-introduction gate (#163), keyed per child + allergen. Once set,
 * [de.rochefort.childmonitor.tracker.guidance.AllergenIntroGate] stops blocking
 * that allergen's first exposure for that child.
 *
 * Stored as a simple private-prefs boolean; this is a parent attestation, not a
 * medical record, so it does not belong in the tracker event DB.
 */
object AllergenIntroOverrideStore {

    private const val PREFS = "AllergenIntroOverride"

    private fun key(childId: String, allergen: String) = "$childId::${allergen.uppercase()}"

    fun isClearedByClinician(context: Context, childId: String?, allergen: String): Boolean {
        if (childId.isNullOrBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(childId, allergen), false)
    }

    fun markClearedByClinician(context: Context, childId: String?, allergen: String) {
        if (childId.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(childId, allergen), true)
            .apply()
    }
}
