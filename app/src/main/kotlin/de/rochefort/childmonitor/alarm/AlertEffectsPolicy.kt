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
package de.rochefort.childmonitor.alarm

import android.content.SharedPreferences

/**
 * #357: the parent disconnect-alert delivery channels as INDEPENDENT toggles, replacing the old
 * single AlertStyle enum (normal / vibration-only / deaf-HoH). Each effect is on/off-able on its
 * own from the in-app Settings screen, so a user can pick any combination (e.g. "vibrate + flash,
 * no torch", or "flash only"). Deaf/HoH is simply sound off + vibration/flash/torch on.
 */
data class AlertEffects(
    val sound: Boolean,
    val vibration: Boolean,
    val screenFlash: Boolean,
    val torch: Boolean
) {
    fun any(): Boolean = sound || vibration || screenFlash || torch
    /** The visual/tactile (non-audible) channels — these fire even when alert SOUND is muted. */
    fun anyNonAudible(): Boolean = vibration || screenFlash || torch
}

object AlertEffectsPolicy {

    // Persisted in "ListenPreferences" as four booleans + a "configured" marker so we know whether
    // to migrate from the old single-style pref on first read after upgrade.
    private const val K_SET = "alertEffectsSet"
    private const val K_SOUND = "alertSound"
    private const val K_VIBRATION = "alertVibration"
    private const val K_FLASH = "alertScreenFlash"
    private const val K_TORCH = "alertTorch"
    /** The pre-#357 single-style pref key (AlertStylePolicy), read once for migration. */
    const val LEGACY_STYLE_KEY = "alertStyle"

    /** Default preserves the prior NORMAL behaviour: audible only (the notification still vibrates
     *  via its channel; the explicit vibration/flash/torch effects are opt-in). */
    val DEFAULT = AlertEffects(sound = true, vibration = false, screenFlash = false, torch = false)

    /** Map the old single-style pref value to the equivalent effect set (one-time upgrade path). */
    fun migrate(legacyStyle: String?): AlertEffects = when (legacyStyle?.trim()?.lowercase()) {
        "vibration_only", "vibration", "vibrate" ->
            AlertEffects(sound = false, vibration = true, screenFlash = false, torch = false)
        "high_visibility", "deaf_hoh", "deaf", "hoh" ->
            AlertEffects(sound = false, vibration = true, screenFlash = true, torch = true)
        else -> DEFAULT // "normal" / null / unknown
    }

    fun load(prefs: SharedPreferences): AlertEffects {
        if (!prefs.getBoolean(K_SET, false)) {
            val migrated = migrate(prefs.getString(LEGACY_STYLE_KEY, null))
            save(prefs, migrated)
            return migrated
        }
        return AlertEffects(
            sound = prefs.getBoolean(K_SOUND, DEFAULT.sound),
            vibration = prefs.getBoolean(K_VIBRATION, DEFAULT.vibration),
            screenFlash = prefs.getBoolean(K_FLASH, DEFAULT.screenFlash),
            torch = prefs.getBoolean(K_TORCH, DEFAULT.torch)
        )
    }

    fun save(prefs: SharedPreferences, e: AlertEffects) {
        prefs.edit()
            .putBoolean(K_SET, true)
            .putBoolean(K_SOUND, e.sound)
            .putBoolean(K_VIBRATION, e.vibration)
            .putBoolean(K_FLASH, e.screenFlash)
            .putBoolean(K_TORCH, e.torch)
            .apply()
    }
}
