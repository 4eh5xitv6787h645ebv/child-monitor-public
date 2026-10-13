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
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.FlashOverlayActivity
import de.rochefort.childmonitor.ListenActivity
import de.rochefort.childmonitor.R

/**
 * #289: a high-priority "child disconnected" notification (parent side) that pierces
 * Do-Not-Disturb / silent and wakes the screen, so a parent can't miss losing the baby's
 * audio. Uses a dedicated IMPORTANCE_HIGH channel with `setBypassDnd(true)` + an ALARM-usage
 * sound, posted with `CATEGORY_ALARM` and a **full-screen intent**.
 *
 * Posting is debounced + episode-scoped via [ConnectionAlertState] (the disconnect path
 * fires per reconnect retry): at most one post per sustained outage, none for brief blips,
 * suppressed after the user acknowledges — so a flapping link can't loop a DnD-piercing
 * alarm-volume siren (code-review HIGH).
 *
 * Full-screen intent is best-effort: on Android 14+ it requires the user-revocable
 * USE_FULL_SCREEN_INTENT special access (CATEGORY_ALARM does NOT auto-grant it), so we gate
 * on [NotificationManager.canUseFullScreenIntent]; when not granted it degrades to a
 * high-priority heads-up. (Mirrors the project's AllergenActivity FSI pattern.)
 */
class ConnectionAlertNotifier(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val state = ConnectionAlertState()

    init {
        ensureChannel()
    }

    /**
     * Feed a disconnect tick (per retry); posts at most once per sustained outage. Returns true on
     * the episode's first post (after the debounce) so the caller can gate one-shot cues (#347) on
     * the same episode-scoped debounce — exactly one beep/vibration per outage, none for a blip.
     */
    fun onDisconnect(nowMs: Long): Boolean {
        val firstPost = state.onDisconnect(nowMs)
        if (firstPost) post()
        return firstPost
    }

    /** Connection (re)established: re-arm + clear the notification. */
    fun reset() {
        state.onReconnect()
        clear()
    }

    /** User acknowledged (opened the app): suppress until reconnect + clear. */
    fun acknowledge() {
        state.acknowledge()
        clear()
    }

    private fun post() {
        if (!postPermitted()) return
        // #357: per-effect. Route to the silent channel whenever the sound effect is off (the
        // alerter handles vibration/torch, the FSI handles flash — the notification adds no sound).
        val effects = AlertEffectsPolicy.load(
            context.getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE)
        )
        val channelId = channelFor(effects.sound, effects.vibration)
        val open = Intent(context, ListenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, REQUEST_CODE, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // #303/#357: when the screen-flash effect is on, the full-screen intent launches the
        // strobing flash overlay (the FSI is the sanctioned way to start an activity from the
        // background on Android 12+). Tapping the notification still opens the app (contentIntent).
        val fsiPi = if (effects.screenFlash) {
            val flash = Intent(context, FlashOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            PendingIntent.getActivity(
                context, REQUEST_CODE_FLASH, flash,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else pi
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle(context.getString(R.string.connAlertTitle))
            .setContentText(context.getString(R.string.connAlertBody))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        // Pre-O has no channels: the builder controls sound/vibration per the effect toggles.
        // On O+ the channel (selected above) decides and the builder is ignored.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (effects.vibration) builder.setVibrate(VIBRATION_PATTERN)
            if (!effects.sound) builder.setSound(null)
        }
        // Full-screen intent only if actually permitted (Android 14+ special access).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(fsiPi, true)
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
            Log.i(TAG, "Posted disconnect alert (effects=$effects)")
        } catch (e: SecurityException) {
            Log.e(TAG, "notify() SecurityException", e)
        }
    }

    private fun clear() {
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
        // #357: channel settings (sound/vibration) are immutable after creation, so to honour the
        // independent Sound and Vibration toggles we keep one channel per (sound × vibration) combo
        // and route to the matching one at post() time.
        ensureOne(CHANNEL_ID, "Connection alerts", sound = true, vibrate = true)
        ensureOne(CHANNEL_ID_SOUND, "Connection alerts (no vibration)", sound = true, vibrate = false)
        ensureOne(CHANNEL_ID_VIB, "Connection alerts (vibration only)", sound = false, vibrate = true)
        ensureOne(CHANNEL_ID_SILENT, "Connection alerts (silent)", sound = false, vibrate = false)
    }

    /**
     * #320: a channel's DnD-bypass is fixed at creation and is silently stored as false unless the
     * app holds Notification Policy access at that moment. So once the user grants that access, the
     * existing channels still don't bypass — delete + recreate them (now that access is held) so the
     * setBypassDnd(true) sticks. No-op if access isn't granted or the channels already bypass.
     */
    fun refreshDndBypass() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!manager.isNotificationPolicyAccessGranted) return
        val ids = listOf(CHANNEL_ID, CHANNEL_ID_SOUND, CHANNEL_ID_VIB, CHANNEL_ID_SILENT)
        // Only recreate the channels that don't yet bypass — leave already-bypassing ones (and any
        // the user disabled) untouched. There's a microsecond window where a deleted-and-not-yet-
        // recreated channel would drop a post; accepted because this runs only from Settings.onResume
        // (user actively in-app, not asleep relying on the monitor) and a still-down link re-posts on
        // the next reconnect tick.
        val stale = ids.filter { manager.getNotificationChannel(it)?.canBypassDnd() == false }
        if (stale.isEmpty()) return
        stale.forEach { manager.deleteNotificationChannel(it) }
        ensureChannel() // recreates only the deleted ids (existing ones early-return in ensureOne)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun ensureOne(id: String, name: String, sound: Boolean, vibrate: Boolean) {
        if (manager.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "High-priority alert when the link to the child device is lost"
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = VIBRATION_PATTERN
            if (sound) {
                setSound(
                    android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else {
                setSound(null, null)
            }
        }
        manager.createNotificationChannel(ch)
    }

    /** #357: pick the channel matching the Sound/Vibration effect toggles (see [ensureChannel]). */
    private fun channelFor(sound: Boolean, vibration: Boolean): String = when {
        sound && vibration -> CHANNEL_ID
        sound && !vibration -> CHANNEL_ID_SOUND
        !sound && vibration -> CHANNEL_ID_VIB
        else -> CHANNEL_ID_SILENT
    }

    companion object {
        private const val TAG = "ConnAlertNotifier"
        const val CHANNEL_ID = "cm_connection_alert"
        // #292: silent (vibration + visual only) variant of the disconnect alert channel.
        const val CHANNEL_ID_VIB = "cm_connection_alert_vib"
        // #357: the remaining (sound × vibration) combos so the notification honours each toggle.
        const val CHANNEL_ID_SOUND = "cm_connection_alert_sound"   // sound, no vibration
        const val CHANNEL_ID_SILENT = "cm_connection_alert_silent" // no sound, no vibration
        const val NOTIFICATION_ID = 4289
        private const val REQUEST_CODE = 4289
        // #303: distinct request code so the deaf/HoH flash-overlay FSI doesn't collide with the
        // ListenActivity content-intent PendingIntent.
        private const val REQUEST_CODE_FLASH = 4290
        private const val LISTEN_PREFS = "ListenPreferences"
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)
    }
}
