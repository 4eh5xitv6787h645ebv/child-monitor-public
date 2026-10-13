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

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.util.AlarmMode
import de.rochefort.childmonitor.util.AlarmScheduling

/**
 * AlarmManager-driven watchdog that resurrects [ListenService] when Android kills the
 * parent process unexpectedly. The Pixel 8 Pro on Android 16 was observed killing the
 * process 1-2 times per day via "REMOVE_TASK" and overnight via "SIGNALED" — once the
 * process dies, the foreground notification disappears and audio stops, but the recents
 * task snapshot persists, so the user thinks the app is still running.
 *
 * Behaviour:
 *  - [scheduleNext] sets a single exact-and-allow-while-idle alarm [INTERVAL_MS] from now.
 *  - When the alarm fires, [onReceive] checks the "sessionActive" flag in
 *    `ListenPreferences`. If set and [ListenService] is not currently running, it
 *    restarts the service with the persisted address/port/name. Then it re-arms itself.
 *  - If "sessionActive" is false the alarm stops re-arming, going dormant until the
 *    user starts a new session.
 *  - [cancel] cancels the pending alarm — [stopSession] invokes it when the user explicitly
 *    stops monitoring. Force-stop also clears the alarm automatically on Android 14+.
 *
 * Permission: relies on USE_EXACT_ALARM (auto-granted for calendar/alarm/messaging apps;
 * baby-monitor's safety-critical wake fits the policy). Falls back to setAndAllowWhileIdle
 * if the exact alarm is denied — on a battery-whitelisted app that still wakes during Doze.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "CE storage locked — will retry next alarm cycle")
            scheduleNext(context)
            return
        }

        val sessionActive = prefs.getBoolean(PREF_SESSION_ACTIVE, false)
        if (!sessionActive) {
            Log.i(TAG, "Watchdog tick: session inactive, going dormant")
            return
        }

        if (isListenServiceRunning(context)) {
            Log.d(TAG, "Watchdog tick: ListenService alive")
            scheduleNext(context)
            return
        }

        val address = prefs.getString(PREF_SESSION_ADDRESS, null)
        val port = prefs.getInt(PREF_SESSION_PORT, 0)
        val name = prefs.getString(PREF_SESSION_NAME, null) ?: address
        val deviceId = prefs.getString(PREF_SESSION_DEVICE_ID, null)?.takeIf { it.isNotBlank() }

        if (address.isNullOrBlank() || port <= 0) {
            Log.w(TAG, "Watchdog: sessionActive=true but no valid address/port — clearing flag")
            prefs.edit().putBoolean(PREF_SESSION_ACTIVE, false).apply()
            return
        }

        Log.w(TAG, "Watchdog: ListenService dead, resurrecting for $address:$port")
        val serviceIntent = Intent(context, ListenService::class.java).apply {
            putExtra("address", address)
            putExtra("port", port)
            putExtra("name", name)
            deviceId?.let { putExtra("deviceId", it) }
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: failed to restart ListenService", e)
        }
        scheduleNext(context)
    }

    private fun isListenServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        // Note: getRunningServices is deprecated on API 26+ but still returns the caller's
        // own services, which is exactly what we want here.
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ListenService::class.java.name }
    }

    companion object {
        private const val TAG = "ChildMonitorWatchdog"
        private const val PREFS_NAME = "ListenPreferences"
        const val PREF_SESSION_ACTIVE = "sessionActive"
        const val PREF_SESSION_ADDRESS = "sessionAddress"
        const val PREF_SESSION_PORT = "sessionPort"
        const val PREF_SESSION_NAME = "sessionName"
        const val PREF_SESSION_DEVICE_ID = "sessionDeviceId"

        // 90s catches dead services within ~1.5 min of any wake. Android 13+ rate-limits
        // exact-while-idle to ~9 min per app during deep doze, so on a fully idle device
        // the actual cadence may stretch to that — still vastly better than the current
        // "stays dead until user notices" failure mode.
        private const val INTERVAL_MS = 90_000L
        private const val REQUEST_CODE = 0xCAFE

        /**
         * Mark the session active and arm the watchdog. Idempotent — call this every time
         * [ListenService.onStartCommand] starts a new listening session so the persisted
         * parameters stay fresh even if the user reconnects to a different child device.
         */
        fun startSession(
            context: Context,
            address: String,
            port: Int,
            name: String?,
            deviceId: String? = null,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(PREF_SESSION_ACTIVE, true)
                .putString(PREF_SESSION_ADDRESS, address)
                .putInt(PREF_SESSION_PORT, port)
                .putString(PREF_SESSION_NAME, name ?: address)
                .apply {
                    if (deviceId.isNullOrBlank()) remove(PREF_SESSION_DEVICE_ID)
                    else putString(PREF_SESSION_DEVICE_ID, deviceId)
                }
                .apply()
            scheduleNext(context)
        }

        /**
         * Clear the session flag and cancel the pending alarm. Call from an explicit
         * user-initiated stop path. (Force-stop from system settings also cancels the
         * alarm automatically.)
         */
        fun stopSession(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SESSION_ACTIVE, false)
                .apply()
            cancel(context)
        }

        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = buildPendingIntent(context, noCreate = false) ?: run {
                Log.w(TAG, "scheduleNext: PendingIntent.getBroadcast returned null")
                return
            }
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            // [#229] canScheduleExactAlarms() is API 31+, so only evaluate it on S+.
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                when (AlarmScheduling.chooseMode(Build.VERSION.SDK_INT, exactAllowed)) {
                    AlarmMode.EXACT_LEGACY ->
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    AlarmMode.EXACT_WHILE_IDLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    } else {
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    }
                    AlarmMode.INEXACT_WHILE_IDLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    } else {
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                    }
                }
            } catch (e: SecurityException) {
                // Only reachable on API 31+ where exact alarms can be policy-denied; the
                // while-idle setter exists there. (#229)
                Log.w(TAG, "Exact alarm denied — falling back to inexact while-idle", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                    )
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = buildPendingIntent(context, noCreate = true) ?: return
            am.cancel(pi)
            pi.cancel()
        }

        private fun buildPendingIntent(context: Context, noCreate: Boolean): PendingIntent? {
            val intent = Intent(context, WatchdogReceiver::class.java)
            var flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            if (noCreate) flags = flags or PendingIntent.FLAG_NO_CREATE
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }
    }
}
