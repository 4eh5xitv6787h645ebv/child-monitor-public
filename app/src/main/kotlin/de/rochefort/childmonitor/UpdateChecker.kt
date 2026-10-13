package de.rochefort.childmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Lightweight update checker (#210): once a day, asks the GitHub releases API
 * whether a newer version has shipped and, if so, posts a dismissible
 * notification linking to the release page.
 *
 * Deliberately a notify-only design — it never downloads or installs an APK, so
 * it needs no install permission and runs no remote code; the user installs the
 * update themselves from the release page. The network call is HTTPS-only and
 * failure-silent, and the decision logic lives in the pure, unit-tested
 * [UpdateCheck]. The page URL is validated as an https github.com address before
 * it is ever handed to an ACTION_VIEW intent.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/4eh5xitv6787h645ebv/child-monitor/releases/latest"
    private const val PREFS = "UpdateChecker"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_ENABLED = "auto_check_enabled"

    /** #297: whether the once-a-day GitHub update check runs. Default on; user-controllable in
     *  Settings so anyone who wants zero outbound traffic can guarantee it. */
    fun isAutoCheckEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    private val CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)
    private const val NOTIF_CHANNEL = "cm_updates"
    private const val NOTIF_ID = 0x0DDED
    private const val MAX_RESPONSE_CHARS = 512 * 1024

    /** Fire a throttled background check. Safe to call on every launch. */
    fun maybeCheck(context: Context) {
        val appCtx = context.applicationContext
        if (!isAutoCheckEnabled(appCtx)) return // #297: user opted out of all outbound traffic
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!UpdateCheck.shouldCheck(prefs.getLong(KEY_LAST_CHECK, 0L), now, CHECK_INTERVAL_MS)) return
        // Record the attempt up front so a flaky network doesn't re-hit GitHub on
        // every launch within the interval.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        Thread {
            try {
                val current = currentVersionName(appCtx) ?: return@Thread
                val body = fetch(LATEST_RELEASE_URL) ?: return@Thread
                val release = UpdateCheck.parseLatestRelease(body) ?: return@Thread
                if (UpdateCheck.isNewer(release.versionName, current)) {
                    notifyUpdate(appCtx, release)
                }
            } catch (e: Exception) {
                LogManager.w(LogManager.LogCategory.SYSTEM, "Update check failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "cm-update-check" }.start()
    }

    private fun currentVersionName(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    private fun fetch(urlStr: String): String? {
        // Cast enforces HTTPS — a plaintext URL would throw (and is caught upstream).
        val conn = java.net.URL(urlStr).openConnection() as HttpsURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "child-monitor-update-check")
            if (conn.responseCode != 200) null else readBounded(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** Read the response, refusing anything larger than [MAX_RESPONSE_CHARS] — a
     *  releases/latest payload is a few KB, so this only ever trips on a
     *  pathological response and never buffers it unbounded. */
    private fun readBounded(conn: HttpsURLConnection): String? {
        conn.inputStream.bufferedReader().use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(16 * 1024)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.length > MAX_RESPONSE_CHARS) return null
            }
            return sb.toString()
        }
    }

    private fun notifyUpdate(context: Context, release: UpdateCheck.Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_body, release.versionName))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, notification)
    }
}
