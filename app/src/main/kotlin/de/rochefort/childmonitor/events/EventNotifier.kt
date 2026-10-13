package de.rochefort.childmonitor.events

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
import de.rochefort.childmonitor.LogManager
import de.rochefort.childmonitor.R

/**
 * Local push notifications to parent app for events.
 * Implements CM-ALT-003 (local push notifications) and CM-UTL-002 (critical alerts).
 *
 * Uses Android notification channels so the user can mute individual event
 * categories without silencing safety alerts (battery critical, camera down).
 */
class EventNotifier(private val context: Context) {

    enum class Kind(val channelId: String, val channelName: String, val importance: Int) {
        CRY("cm_cry", "Cry/Sound", NotificationManager.IMPORTANCE_HIGH),
        MOTION("cm_motion", "Motion", NotificationManager.IMPORTANCE_DEFAULT),
        CRITICAL("cm_critical", "Device Health", NotificationManager.IMPORTANCE_HIGH),
        INFO("cm_info", "Info", NotificationManager.IMPORTANCE_LOW)
    }

    init {
        ensureChannels()
    }

    /** @return true when Android accepted the notification, false when permission/policy blocked it. */
    fun notify(kind: Kind, title: String, body: String, openIntent: Intent? = null): Boolean {
        // On API 33+ POST_NOTIFICATIONS is a runtime permission. If the user denied
        // it, NotificationManager.notify() throws SecurityException, and our cry
        // alerts (IMPORTANCE_HIGH) and CRITICAL device-health alerts silently fail
        // — exactly the wrong direction for a baby monitor (#187 finding 5).
        // Check first, log loudly, and bail. The caller (or top-level UI) is
        // responsible for re-requesting POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "Skipping ${kind.name} notification — POST_NOTIFICATIONS denied")
                LogManager.e(
                    LogManager.LogCategory.SYSTEM,
                    "EventNotifier ${kind.name} '$title' DROPPED — POST_NOTIFICATIONS denied. " +
                        "Critical alerts (cry, device-health) are currently invisible. " +
                        "User must re-grant the permission in Settings."
                )
                return false
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, kind.channelId)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (openIntent != null) {
            val pi = PendingIntent.getActivity(
                context,
                kind.ordinal,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pi)
        }
        try {
            manager.notify(kind.hashCode(), builder.build())
            return true
        } catch (e: SecurityException) {
            // Defensive: even with the permission check above, OEM-specific
            // modes (e.g. app suspension) can still throw SecurityException.
            Log.e(TAG, "notify() threw SecurityException for ${kind.name}", e)
            LogManager.e(LogManager.LogCategory.SYSTEM, "EventNotifier ${kind.name} SecurityException", e)
            return false
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (kind in Kind.values()) {
            val existing = manager.getNotificationChannel(kind.channelId)
            if (existing == null) {
                val ch = NotificationChannel(kind.channelId, kind.channelName, kind.importance).apply {
                    description = "Child Monitor: ${kind.channelName}"
                }
                manager.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val TAG = "EventNotifier"
    }
}
