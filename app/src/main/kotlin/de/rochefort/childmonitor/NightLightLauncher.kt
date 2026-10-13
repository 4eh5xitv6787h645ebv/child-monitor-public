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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * #312: BAL-safe launcher for [NightLightActivity]. A backgrounded foreground service cannot call
 * startActivity() on Android 12+, so the cold launch (the child phone is the exact "locked/dozing"
 * case) goes through a **full-screen-intent** notification — the sanctioned mechanism, mirroring
 * [de.rochefort.childmonitor.alarm.ConnectionAlertNotifier]. Silent (it's a lamp, not an alert).
 *
 * We ALSO fire a plain startActivity: it covers the emulator and the case where the app is already
 * foreground (the night-light is showing), where it delivers onNewIntent to the singleTop instance
 * for live colour/brightness updates and relaunches it if the OS reclaimed it.
 *
 * FSI is best-effort on Android 14+ (the user-revocable USE_FULL_SCREEN_INTENT special access);
 * we gate on [NotificationManager.canUseFullScreenIntent] and degrade to a tap notification.
 */
class NightLightLauncher(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannel() }

    /** Launch or raise the night-light with the given colour/brightness (idempotent). */
    fun show(color: Int, brightness: Float) {
        val launch = Intent(context, NightLightActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NightLightActivity.EXTRA_ON, true)
            putExtra(NightLightActivity.EXTRA_COLOR, color)
            putExtra(NightLightActivity.EXTRA_BRIGHTNESS, brightness)
        }
        val pi = PendingIntent.getActivity(
            context, REQUEST_CODE, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle("Night light")
            .setContentText("Showing on this device — tap to manage")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pi)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(pi, true)
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "night-light notify failed", e)
        }
        // Direct start: works on the emulator and when the app is already foreground (live update
        // via onNewIntent). Harmlessly blocked-and-logged when backgrounded — the FSI covers that.
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "night-light startActivity blocked (FSI will cover cold launch): ${e.message}")
        }
    }

    /** Remove the launcher notification (the activity itself is dismissed via broadcast). */
    fun hide() {
        try { manager.cancel(NOTIFICATION_ID) } catch (e: Exception) { Log.w(TAG, "cancel failed", e) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_HIGH is required for the full-screen intent to fire, but silent (no sound /
        // no vibration) so the lamp never makes noise.
        val ch = NotificationChannel(CHANNEL_ID, "Night light", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shows the remotely-controlled night-light on this device"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "NightLightLauncher"
        const val CHANNEL_ID = "cm_night_light"
        const val NOTIFICATION_ID = 4312
        private const val REQUEST_CODE = 4312
    }
}
