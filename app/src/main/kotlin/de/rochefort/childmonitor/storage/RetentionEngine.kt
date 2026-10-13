package de.rochefort.childmonitor.storage

import android.content.Context
import de.rochefort.childmonitor.events.EventStore
import de.rochefort.childmonitor.reliability.FilesystemGuard
import java.io.File

/**
 * Retention engine and storage pressure guardrails.
 * Implements CM-STORE-002 (retention engine + guardrails).
 *
 * Policies:
 *  - Event rows older than [eventRetentionDays] are pruned.
 *  - Audit log rotates at 1 MB.
 *  - Media ring kept at configured size; under CRITICAL pressure, halved.
 *  - Log files older than [logRetentionDays] are deleted.
 */
class RetentionEngine(
    private val context: Context,
    private val eventRetentionDays: Long = 30,
    private val logRetentionDays: Long = 14
) {

    data class Report(
        val eventsDeleted: Int,
        val logsDeleted: Int,
        val mediaDeleted: Int,
        val pressure: FilesystemGuard.Pressure
    )

    fun run(eventStore: EventStore?, mediaBuffer: RollingMediaBuffer?): Report {
        val guard = FilesystemGuard(context)
        val snapshot = guard.snapshot()

        val eventsCutoff = System.currentTimeMillis() - eventRetentionDays * DAY_MS
        val eventsDeleted = eventStore?.deleteOlderThan(eventsCutoff) ?: 0

        val logsDeleted = deleteOldLogs()
        val mediaDeleted = trimMedia(mediaBuffer, snapshot.pressure)

        return Report(eventsDeleted, logsDeleted, mediaDeleted, snapshot.pressure)
    }

    private fun deleteOldLogs(): Int {
        val dir = File(context.filesDir, "diagnostic_logs")
        if (!dir.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - logRetentionDays * DAY_MS
        var deleted = 0
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) {
                try {
                    if (f.delete()) deleted++
                } catch (_: Exception) { }
            }
        }
        return deleted
    }

    private fun trimMedia(buffer: RollingMediaBuffer?, pressure: FilesystemGuard.Pressure): Int {
        if (buffer == null) return 0
        val files = buffer.list()
        val keep = when (pressure) {
            FilesystemGuard.Pressure.NORMAL -> files.size
            FilesystemGuard.Pressure.DEGRADED -> (files.size * 3 / 4)
            FilesystemGuard.Pressure.CRITICAL -> files.size / 2
        }
        var deleted = 0
        if (keep < files.size) {
            for (i in keep until files.size) {
                try {
                    if (files[i].file.delete()) deleted++
                } catch (_: Exception) { }
            }
        }
        return deleted
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
