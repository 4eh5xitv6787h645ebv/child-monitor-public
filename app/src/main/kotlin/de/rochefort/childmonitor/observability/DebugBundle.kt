package de.rochefort.childmonitor.observability

import android.content.Context
import de.rochefort.childmonitor.reliability.SessionStateMachine
import de.rochefort.childmonitor.security.AuditLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * In-app diagnostics page + one-tap local debug bundle export.
 * Implements CM-OBS-002.
 *
 * Produces a zip of recent logs, the audit trail, metrics snapshot, session
 * checkpoints, and the 60s packet ring. Stored in filesDir/bundles/ so the
 * FileProvider can share it without exposing other app-private data.
 */
object DebugBundle {

    fun export(context: Context, status: JSONObject, sessions: JSONObject, ring: PacketRingBuffer): File {
        val dir = File(context.filesDir, "bundles").also { it.mkdirs() }
        val name = "bundle-${System.currentTimeMillis()}.zip"
        val file = File(dir, name)
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zip ->
                writeEntry(zip, "status.json", status.toString(2))
                writeEntry(zip, "sessions.json", sessions.toString(2))
                writeEntry(zip, "audit_log.jsonl", auditLogLines(context))
                writeEntry(zip, "metrics.json", Metrics.asJson().toString(2))
                writeEntry(zip, "packet_ring.json", ring.dump().toString(2))
                writeEntry(zip, "session_checkpoints.json", checkpoints(context).toString(2))
                includeLogs(zip, context)
            }
        }
        return file
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun includeLogs(zip: ZipOutputStream, context: Context) {
        val dir = File(context.filesDir, "diagnostic_logs")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            zip.putNextEntry(ZipEntry("logs/${f.name}"))
            try {
                f.inputStream().copyTo(zip)
            } catch (_: Exception) {
                // Skip unreadable logs.
            }
            zip.closeEntry()
        }
    }

    private fun auditLogLines(context: Context): String {
        val arr = AuditLog.recent(context, limit = 500)
        return arr.toString(2)
    }

    private fun checkpoints(context: Context): JSONArray {
        val arr = JSONArray()
        for (cp in SessionStateMachine.allCheckpoints(context)) arr.put(cp)
        return arr
    }
}
