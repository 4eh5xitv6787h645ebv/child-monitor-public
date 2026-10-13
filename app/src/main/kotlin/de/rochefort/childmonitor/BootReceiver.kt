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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.rochefort.childmonitor.reliability.DeferredBootPolicy

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!DeferredBootPolicy.isBootAction(action)) return

        // Never start camera/microphone services during the fragile direct-boot phase. If the
        // phone is rebooting repeatedly, LOCKED_BOOT_COMPLETED can fire on every partial boot.
        // Waiting for BOOT_COMPLETED ensures Android has reached its unlocked, usable state; the
        // deferred receiver then adds a further one-minute stability window.
        if (!DeferredBootPolicy.shouldScheduleAfter(action)) {
            Log.i(TAG, "Boot ($action): waiting for fully booted BOOT_COMPLETED")
            return
        }

        // A few OEM quick-boot implementations can also arrive before credential-encrypted
        // storage is available. Do not crash there; a normal BOOT_COMPLETED can still retry.
        val prefs = try {
            context.getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Boot ($action): CE storage locked, deferring to BOOT_COMPLETED after unlock")
            return
        }
        val autoStartChild = prefs.getBoolean(AutoStartGate.KEY_CHILD, false)
        val autoStartParent = prefs.getBoolean(AutoStartGate.KEY_PARENT, false)

        // Migration from the old five-attempt guard: it is no longer consulted and must never
        // disable either role. The user's auto-start choice remains authoritative.
        prefs.edit().remove(LEGACY_PREF_AUTOSTART_ATTEMPTS).apply()

        // If a parent session was active before reboot, re-arm the watchdog. AlarmManager
        // alarms do NOT survive reboot, so without this the watchdog stops checking after
        // the device restarts. The watchdog's own tick will (re)start ListenService.
        if (prefs.getBoolean(WatchdogReceiver.PREF_SESSION_ACTIVE, false)) {
            Log.i(TAG, "Boot ($action): previous session was active, re-arming watchdog")
            WatchdogReceiver.scheduleNext(context)
        }

        if (!autoStartChild && !autoStartParent) {
            Log.i(TAG, "Boot ($action) but auto-start disabled for both roles")
            return
        }

        DeferredBootReceiver.schedule(context)
        Log.i(TAG, "Boot ($action): auto-start scheduled after ${DeferredBootPolicy.START_DELAY_MS / 1000}s stability window")
    }

    companion object {
        private const val TAG = "ChildMonitor"
        private const val LEGACY_PREF_AUTOSTART_ATTEMPTS = "autoStartBootAttempts"
    }
}
