/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor.tracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import de.rochefort.childmonitor.events.EventNotifier
import de.rochefort.childmonitor.util.AlarmMode
import de.rochefort.childmonitor.util.AlarmScheduling

/**
 * #158 partial (CM-TRK-REV-016): fires an INFO notification telling the parent
 * that the next safe dose of a medication is now allowed. Scheduled by
 * MedicationEntryActivity after a successful save when MedicationDose.recommend
 * returned a non-null nextSafeDoseAtMs.
 *
 * Reminders are best-effort: USE_EXACT_ALARM permission (already declared for
 * WatchdogReceiver) lets us schedule exact wake-ups, but the watchdog's own
 * policy_permission applies. If the user has Battery Optimizations on for the
 * app the alarm may fire late — acceptable for a "you can dose again now"
 * reminder which is allowed to be slightly stale.
 */
class MedicationReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val drug = intent.getStringExtra(EXTRA_DRUG) ?: return
        val childName = intent.getStringExtra(EXTRA_CHILD_NAME) ?: "your child"
        try {
            EventNotifier(context).notify(
                EventNotifier.Kind.INFO,
                title = "Next safe $drug dose allowed",
                body = "Based on the last dose, $drug is now an option for $childName if needed."
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to surface medication reminder", e)
        }
    }

    companion object {
        private const val TAG = "MedReminder"
        private const val EXTRA_DRUG = "drug"
        private const val EXTRA_CHILD_NAME = "child_name"
        private const val REQUEST_CODE_BASE = 0xD05E

        /**
         * Schedule a one-shot reminder for [whenWallMs]. If [whenWallMs] is in
         * the past, no alarm is set. One reminder per (drug, childId) — the
         * PendingIntent is FLAG_UPDATE_CURRENT keyed off a per-pair request
         * code so a later dose's schedule replaces the earlier one.
         */
        fun schedule(
            context: Context,
            drug: String,
            childId: String,
            childName: String,
            whenWallMs: Long
        ) {
            if (whenWallMs <= System.currentTimeMillis()) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, MedicationReminderReceiver::class.java).apply {
                putExtra(EXTRA_DRUG, drug)
                putExtra(EXTRA_CHILD_NAME, childName)
            }
            val requestCode = REQUEST_CODE_BASE + (drug.hashCode() xor childId.hashCode()) and 0x7FFFFFFF
            val pi = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ) ?: return
            val triggerElapsed = SystemClock.elapsedRealtime() + (whenWallMs - System.currentTimeMillis())
            try {
                // [#229] canScheduleExactAlarms() is API 31+; setExactAndAllowWhileIdle /
                // setAndAllowWhileIdle are API 23+. Pre-M falls back to setExact.
                val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                when (AlarmScheduling.chooseMode(Build.VERSION.SDK_INT, exactAllowed)) {
                    AlarmMode.EXACT_LEGACY ->
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                    AlarmMode.EXACT_WHILE_IDLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                    } else {
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                    }
                    AlarmMode.INEXACT_WHILE_IDLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                    } else {
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                    }
                }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
                }
            }
        }

        /** Cancel the per-child/per-drug alarm when its dose is undone or removed. */
        fun cancel(context: Context, drug: String, childId: String) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val requestCode = REQUEST_CODE_BASE + (drug.hashCode() xor childId.hashCode()) and 0x7FFFFFFF
            val intent = Intent(context, MedicationReminderReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) ?: return
            am.cancel(pi)
            pi.cancel()
        }
    }
}
