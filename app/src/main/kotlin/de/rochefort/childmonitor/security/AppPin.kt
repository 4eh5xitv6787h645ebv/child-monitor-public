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
 */
package de.rochefort.childmonitor.security

import android.content.Context

/**
 * #375: storage for the OPTIONAL in-app app-lock PIN. The PIN is an *additional* gate layered on top
 * of the existing device-credential unlock ([AppLock]) — it can only be set while the device is
 * secure, so the device-credential prompt is always available as a "forgot PIN" fallback. Only the
 * salted hash + per-install salt are persisted (never the PIN); see [PinHasher]. Wrong-attempt
 * accounting feeds [PinPolicy]'s strictly time-bounded backoff. Nothing here can permanently lock the
 * user out — there is no persistent "locked forever" flag, and clearing the PIN always works.
 */
object AppPin {

    private const val PREFS = "ListenPreferences"
    const val KEY_HASH = "appLockPinHash"
    const val KEY_SALT = "appLockPinSalt"
    const val KEY_FAILS = "appLockPinFails"
    const val KEY_LAST_FAIL = "appLockPinLastFailMs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when a PIN has been configured (both salt and hash present). */
    fun isPinSet(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_HASH, null).isNullOrEmpty() && !p.getString(KEY_SALT, null).isNullOrEmpty()
    }

    /**
     * Set (or replace) the PIN. Rejects an invalid format (returns false) so a bad PIN can never be
     * stored. Generates a fresh salt and resets the wrong-attempt counter.
     */
    fun setPin(context: Context, pin: String): Boolean {
        if (!PinPolicy.isValidPin(pin)) return false
        val salt = PinHasher.newSaltHex()
        prefs(context).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, PinHasher.hash(salt, pin))
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LAST_FAIL, 0L)
            .apply()
        return true
    }

    /** Constant-time check of [pin] against the stored hash. Does NOT mutate attempt state — the
     *  caller records success ([resetAttempts]) or failure ([recordFailure]) so accounting is explicit. */
    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        return PinHasher.matches(p.getString(KEY_SALT, null), pin, p.getString(KEY_HASH, null))
    }

    /** Remove the PIN entirely (and its attempt state). Always succeeds — the fail-safe escape hatch. */
    fun clearPin(context: Context) {
        prefs(context).edit()
            .remove(KEY_HASH).remove(KEY_SALT).remove(KEY_FAILS).remove(KEY_LAST_FAIL)
            .apply()
    }

    fun failedAttempts(context: Context): Int = prefs(context).getInt(KEY_FAILS, 0)

    fun lastFailedAtMs(context: Context): Long = prefs(context).getLong(KEY_LAST_FAIL, 0L)

    /** Record a wrong PIN at [nowMs] (increments the consecutive-failure count). */
    fun recordFailure(context: Context, nowMs: Long) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_FAILS, p.getInt(KEY_FAILS, 0) + 1)
            .putLong(KEY_LAST_FAIL, nowMs)
            .apply()
    }

    /** Clear the consecutive-failure count (after a correct PIN or device-credential unlock). */
    fun resetAttempts(context: Context) {
        prefs(context).edit().putInt(KEY_FAILS, 0).putLong(KEY_LAST_FAIL, 0L).apply()
    }

    /** Convenience: remaining backoff for the current attempt state (0 = may try now). */
    fun remainingBackoffMs(context: Context, nowMs: Long): Long =
        PinPolicy.remainingBackoffMs(failedAttempts(context), lastFailedAtMs(context), nowMs)
}
