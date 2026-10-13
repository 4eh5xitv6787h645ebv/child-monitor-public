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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.ListenActivity
import de.rochefort.childmonitor.R

/**
 * #301 (parent side): a normal-priority notification warning that the *child* (camera) device's
 * battery is low, so the parent can plug it in before it dies mid-nap. Unlike the #289 disconnect
 * alert this is a heads-up warning, not a DnD-piercing alarm — IMPORTANCE_DEFAULT, no bypassDnd.
 * Edge-triggering/debounce is handled by [ChildBatteryAlertState]; this just posts/clears.
 */
class ChildBatteryNotifier(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannel() }

    fun postLow(levelPct: Int) {
        if (!postPermitted()) return
        val open = Intent(context, ListenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, REQUEST_CODE, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle(context.getString(R.string.childBatteryTitle))
            .setContentText(context.getString(R.string.childBatteryBody, levelPct))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
            Log.i(TAG, "Posted child low-battery notification ($levelPct%)")
        } catch (e: SecurityException) {
            Log.e(TAG, "notify() SecurityException", e)
        }
    }

    fun clear() {
        try { manager.cancel(NOTIFICATION_ID) } catch (e: Exception) { Log.w(TAG, "cancel failed", e) }
    }

    private fun postPermitted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, context.getString(R.string.childBatteryChannel), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Warns when the child (camera) device's battery is low"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "ChildBatteryNotifier"
        const val CHANNEL_ID = "cm_child_battery"
        const val NOTIFICATION_ID = 4301
        private const val REQUEST_CODE = 4301
    }
}
