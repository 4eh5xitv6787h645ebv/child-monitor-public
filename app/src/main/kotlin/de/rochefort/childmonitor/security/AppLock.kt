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

import android.app.KeyguardManager
import android.content.Context
import android.os.Build

/**
 * #296: app-lock controller. Holds the process-wide [AppLockGate] state machine and the "enabled"
 * preference, and answers whether the device has a secure lock to confirm against. The actual
 * gating (launching the unlock screen) is wired by the Application's activity-lifecycle callbacks;
 * the unlock itself uses the platform KeyguardManager device-credential prompt (no extra dependency,
 * so the user's existing PIN / pattern / fingerprint / face is the key).
 */
object AppLock {

    private const val PREFS = "ListenPreferences"
    private const val KEY_ENABLED = "appLockEnabled"
    // #375 item 5: one-shot flag set when the lock auto-disables itself because the device screen lock
    // was removed; consumed (read + cleared) to surface a single user notice. Transient runtime state.
    const val KEY_AUTO_DISABLED_NOTICE = "appLockAutoDisabledNotice"

    /** Process-wide foreground gate. Driven on the main thread by ActivityLifecycleCallbacks. */
    val gate = AppLockGate()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** #375 item 5: record that the lock auto-disabled itself (device screen lock removed), so the
     *  next user-facing surface can show a one-time notice that protection was turned off. */
    fun markAutoDisabled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_DISABLED_NOTICE, true).apply()
    }

    /** Read-and-clear the auto-disabled flag; returns true exactly once after an auto-disable. */
    fun consumeAutoDisabledNotice(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_AUTO_DISABLED_NOTICE, false)) return false
        p.edit().remove(KEY_AUTO_DISABLED_NOTICE).apply()
        return true
    }

    /**
     * App-lock can only work if the device has a secure keyguard (PIN/pattern/password/biometric) to
     * confirm against. If there's none, there's nothing to authenticate, so the toggle must not be
     * enabled (the Settings screen checks this) and the gate falls open.
     */
    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            km.isDeviceSecure
        } else {
            @Suppress("DEPRECATION") km.isKeyguardSecure
        }
    }
}
