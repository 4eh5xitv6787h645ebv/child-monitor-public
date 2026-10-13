package de.rochefort.childmonitor

import android.content.Context
import java.io.File

object DiagnosticsRepository {

    data class LogEvent(
        val timestamp: String,
        val level: String,
        val category: String,
        val device: String,
        val message: String
    )

    data class DiagnosticsSummary(
        val errorCount: Int,
        val warningCount: Int,
        val connectionEventCount: Int,
        val disconnectCount: Int,
        val latestError: LogEvent?,
        val latestWarnings: List<LogEvent>
    )

    private val logLineRegex = Regex("^\\[(.+?)] \\[(.+?)] \\[(.+?)] \\[(.+?)] (.*)$")

    fun loadRecentEvents(
        context: Context,
        maxFiles: Int = 5,
        maxLinesPerFile: Int = 600
    ): List<LogEvent> {
        // Ensure log context is available even if activities are opened before services.
        LogManager.initialize(context.applicationContext, LogManager.DeviceType.UNKNOWN)

        val files: List<File> = LogManager.getLogFiles().take(maxFiles)
        if (files.isEmpty()) return emptyList()

        val events = mutableListOf<LogEvent>()
        // Oldest -> newest so final reverse gives newest first.
        for (file in files.reversed()) {
            val lines = try {
                file.readLines().takeLast(maxLinesPerFile)
            } catch (_: Exception) {
                emptyList()
            }
            for (line in lines) {
                parseLine(line)?.let { events.add(it) }
            }
        }
        return events.asReversed()
    }

    fun summarize(events: List<LogEvent>): DiagnosticsSummary {
        val errors = events.filter { it.level == "ERROR" }
        val warnings = events.filter { it.level == "WARNING" }
        val connectionEvents = events.count { it.category == "CONNECTION" }
        val disconnects = events.count {
            it.message.contains("disconnect", ignoreCase = true) ||
                it.message.contains("timed out", ignoreCase = true) ||
                it.message.contains("connection lost", ignoreCase = true)
        }
        return DiagnosticsSummary(
            errorCount = errors.size,
            warningCount = warnings.size,
            connectionEventCount = connectionEvents,
            disconnectCount = disconnects,
            latestError = errors.firstOrNull(),
            latestWarnings = warnings.take(8)
        )
    }

    fun importantEvents(events: List<LogEvent>, maxItems: Int = 20): List<LogEvent> {
        val filtered = events.filter {
            it.level == "ERROR" ||
                it.level == "WARNING" ||
                it.message.contains("disconnect", ignoreCase = true) ||
                it.message.contains("reconnect", ignoreCase = true) ||
                it.message.contains("failed", ignoreCase = true) ||
                it.message.contains("doze", ignoreCase = true) ||
                it.message.contains("timeout", ignoreCase = true) ||
                it.message.contains("battery", ignoreCase = true)
        }
        return filtered.take(maxItems)
    }

    private fun parseLine(line: String): LogEvent? {
        val match = logLineRegex.matchEntire(line) ?: return null
        val (ts, level, category, device, message) = match.destructured
        return LogEvent(ts, level, category, device, message)
    }
}
