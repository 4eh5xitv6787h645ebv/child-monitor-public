/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.reliability.DeferredBootPolicy
import de.rochefort.childmonitor.util.AlarmMode
import de.rochefort.childmonitor.util.AlarmScheduling

/**
 * Starts the configured role only after Android has remained fully booted for one minute.
 * AlarmManager alarms disappear on reboot, so a reboot loop continually resets this timer; once
 * the phone remains stable, Child Mode recovers automatically without anyone touching the device.
 */
class DeferredBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START_AFTER_STABLE_BOOT) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStartChild = prefs.getBoolean(AutoStartGate.KEY_CHILD, false)
        val autoStartParent = prefs.getBoolean(AutoStartGate.KEY_PARENT, false)
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        when {
            autoStartChild && hasAudio && hasCamera -> {
                Log.i(TAG, "Stable boot window complete: auto-starting CHILD role")
                startBootService(context, BootService.ROLE_CHILD, null)
            }
            autoStartParent && hasAudio -> {
                val lastIp = prefs.getString("ipHistory", "")?.split(",")
                    ?.firstOrNull { it.isNotBlank() }
                val hasPairedKid = PairedChildrenStore.list(context).isNotEmpty()
                if (lastIp.isNullOrBlank() && !hasPairedKid) {
                    Log.w(TAG, "Stable boot: parent auto-start has no saved IP or paired kid")
                    return
                }
                Log.i(TAG, "Stable boot window complete: auto-starting PARENT role")
                startBootService(context, BootService.ROLE_PARENT, lastIp)
            }
            autoStartChild || autoStartParent -> {
                Log.w(TAG, "Stable boot reached but permissions are missing " +
                    "(audio=$hasAudio, camera=$hasCamera); keeping auto-start enabled for next boot")
            }
            else -> Log.i(TAG, "Stable boot reached but auto-start was disabled during the wait")
        }
    }

    private fun startBootService(context: Context, role: String, parentAddress: String?) {
        val serviceIntent = Intent(context, BootService::class.java).apply {
            putExtra(BootService.EXTRA_ROLE, role)
            parentAddress?.let { putExtra(BootService.EXTRA_PARENT_IP_PORT, it) }
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // Preserve the preference. A later boot schedules another recovery attempt.
            Log.e(TAG, "Failed to start deferred boot service for role=$role", e)
        }
    }

    companion object {
        private const val TAG = "ChildMonitor"
        private const val PREFS_NAME = "ListenPreferences"
        private const val REQUEST_CODE = 0xB007
        const val ACTION_START_AFTER_STABLE_BOOT =
            "de.rochefort.childmonitor.action.START_AFTER_STABLE_BOOT"

        /** Idempotent: a repeated boot signal replaces the existing timer instead of adding one. */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, DeferredBootReceiver::class.java)
                    .setAction(ACTION_START_AFTER_STABLE_BOOT),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
            )
            val triggerAt = SystemClock.elapsedRealtime() + DeferredBootPolicy.START_DELAY_MS
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            try {
                when (AlarmScheduling.chooseMode(Build.VERSION.SDK_INT, exactAllowed)) {
                    AlarmMode.EXACT_LEGACY -> alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                    )
                    AlarmMode.EXACT_WHILE_IDLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                        )
                    }
                    AlarmMode.INEXACT_WHILE_IDLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact deferred-boot alarm denied; using inexact alarm", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                    )
                }
            }
        }
    }
}
