package de.rochefort.childmonitor.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only local audit log for security-relevant events.
 * Implements CM-SEC-005: security status UI + local audit log.
 *
 * Events: pairing start/complete/reject, session issue/revoke, integrity warnings,
 * HTTP auth failures, biometric challenges.
 */
object AuditLog {

    const val EVT_PAIR_START = "pair_start"
    const val EVT_PAIR_COMPLETE = "pair_complete"
    const val EVT_PAIR_REJECT = "pair_reject"
    const val EVT_PAIR_REVOKE = "pair_revoke"
    const val EVT_SESSION_ISSUE = "session_issue"
    const val EVT_SESSION_ROTATE = "session_rotate"
    const val EVT_SESSION_REFRESH_ISSUE = "session_refresh_issue"
    const val EVT_SESSION_REFRESH = "session_refresh"
    const val EVT_SESSION_REVOKE = "session_revoke"
    const val EVT_AUTH_FAIL = "auth_fail"
    const val EVT_BIOMETRIC_PROMPT = "biometric_prompt"
    const val EVT_INTEGRITY_WARN = "integrity_warn"
    const val EVT_TAMPER_SUSPECT = "tamper_suspect"
    const val EVT_TLS_ROTATE = "tls_rotate"
    const val EVT_CAST_ISSUE = "cast_issue"
    const val EVT_CAST_REVOKE = "cast_revoke"
    // #296/#375 app-lock. EVT_APP_UNLOCK was a private const in LockActivity; centralized here so the
    // device-credential and the new in-app PIN unlock paths share one event name + attrs.
    const val EVT_APP_UNLOCK = "app_unlock"
    const val EVT_APP_PIN_SET = "app_pin_set"
    const val EVT_APP_PIN_CLEAR = "app_pin_clear"

    private const val FILE_NAME = "audit_log.jsonl"
    private const val MAX_BYTES = 1_000_000L
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    @Synchronized
    fun append(context: Context, event: String, attrs: Map<String, Any?> = emptyMap()) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                rotate(file)
            }
            val line = JSONObject().apply {
                put("ts", isoFormat.format(Date()))
                put("tsMs", System.currentTimeMillis())
                put("event", event)
                for ((k, v) in attrs) put(k, v ?: JSONObject.NULL)
            }
            FileOutputStream(file, true).use { out ->
                out.write(line.toString().toByteArray(Charsets.UTF_8))
                out.write("\n".toByteArray(Charsets.UTF_8))
            }
        } catch (_: IOException) {
            // Audit log writes must never crash caller; silently drop on disk errors.
        }
    }

    @Synchronized
    fun recent(context: Context, limit: Int = 100): JSONArray {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return JSONArray()
        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (_: IOException) {
            return JSONArray()
        }
        val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
        val out = JSONArray()
        for (l in tail) {
            try {
                out.put(JSONObject(l))
            } catch (_: Exception) {
                // Skip corrupt lines
            }
        }
        return out
    }

    private fun rotate(file: File) {
        try {
            val backup = File(file.parentFile, "$FILE_NAME.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        } catch (_: Exception) {
            // Can't rotate: truncate to keep append going.
            file.delete()
        }
    }
}
