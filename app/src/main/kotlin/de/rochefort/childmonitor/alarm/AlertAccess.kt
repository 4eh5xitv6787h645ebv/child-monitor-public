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
package de.rochefort.childmonitor.alarm

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * #320: reads the two special accesses that let disconnect/cry alerts truly pierce an ACTIVE
 * Do-Not-Disturb and reliably wake a locked screen, and opens the system grant screens. The alert
 * channel already requests setBypassDnd(true) + a full-screen intent — these accesses make Android
 * honour them.
 */
object AlertAccess {

    fun dndState(context: Context): AccessState {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.isNotificationPolicyAccessGranted ?: false
        } else {
            false
        }
        return AlertAccessPolicy.dnd(Build.VERSION.SDK_INT, granted)
    }

    fun fullScreenIntentState(context: Context): AccessState {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.canUseFullScreenIntent() ?: false
        } else {
            true // auto-granted below API 34
        }
        return AlertAccessPolicy.fullScreenIntent(Build.VERSION.SDK_INT, granted)
    }

    /** Open the system "Do-Not-Disturb access" screen (API 23+). */
    fun openDndAccessSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    /** Open this app's "full-screen intent" special-access screen (API 34+). */
    fun openFullScreenIntentSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + activity.packageName)
                )
            )
        }
    }
}
