package de.rochefort.childmonitor

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList

/**
 * Centralized logging manager for diagnosing connection issues.
 * Logs are kept for 7 days and stored in app's private storage.
 *
 * Log Format: [TIMESTAMP] [LEVEL] [CATEGORY] [DEVICE_TYPE] Message
 * Example: [2025-01-15 14:23:45.123] [INFO] [CONNECTION] [PARENT] Connected to 192.168.1.100:10000
 */
object LogManager {

    private const val LOG_DIR = "diagnostic_logs"
    private const val MAX_LOG_AGE_DAYS = 7
    private const val TAG = "LogManager"
    private const val SESSION_STATE_FILE = "session_state.json"

    // #425: single-thread async writer. LogManager is a process-wide singleton (Kotlin object), so
    // there is exactly ONE executor, created once and shared by every logging call for the life of the
    // process. That is correct — NOT a per-restart leak. It is deliberately NOT shut down on service
    // teardown (shutdownGracefully): the process can outlive a service (e.g. StartActivity keeps
    // logging after MonitorService.onDestroy), and shutting it down would make every later log() throw
    // RejectedExecutionException. A daemon thread so the one long-lived worker can never block a clean
    // process exit.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogManager-writer").apply { isDaemon = true }
    }
    // #425: SimpleDateFormat is NOT thread-safe and these are formatted from arbitrary caller threads
    // (log()/exportAllLogs()) and the executor thread — concurrent format() can garble output or throw.
    // ThreadLocal gives each thread its own instance (works on all minSdk-21 devices).
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // #425: @Volatile — written under @Synchronized initialize() but read from many unsynchronized
    // methods (log/writeToFile/getLogFiles/exportAllLogs/...) on arbitrary threads. Volatile gives
    // those reads a happens-before edge so they can't observe a stale null context / UNKNOWN type.
    @Volatile private var context: Context? = null
    @Volatile private var deviceType: DeviceType = DeviceType.UNKNOWN
    // #425: warn once (not on every line) when logging happens before initialize() so the dropped
    // file write isn't fully silent. The logcat line in log() is still emitted regardless.
    private val warnedBeforeInit = AtomicBoolean(false)

    enum class DeviceType {
        PARENT,  // Listener device
        CHILD,   // Monitor device
        UNKNOWN
    }

    enum class LogLevel {
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }

    enum class LogCategory {
        CONNECTION,    // Connection attempts, successes, failures, disconnects
        NETWORK,       // WiFi state, network changes, signal strength, IP changes
        AUDIO,         // Audio streaming metrics, buffer status, codec info
        SYSTEM,        // Battery, power saving mode, service lifecycle
        DISCOVERY,     // NSD/mDNS service discovery events
        HEALTH         // Health checks, keepalive, timeout events
    }

    /**
     * Initialize the log manager with application context.
     *
     * Writes a session banner only when transitioning to a known role (CHILD/PARENT).
     * Previously every StartActivity open also wrote a banner with `Device Type: UNKNOWN`,
     * cluttering the diagnostic log with unhelpful entries (7+/day observed on 2026-04-18).
     *
     * Synchronized so concurrent initialize() calls (rare — happens during role-switch
     * debug or if both services are bound in the same process) don't both see
     * previousType=UNKNOWN, both pass the type-changed gate, and both emit duplicate
     * session-state markers (review-pass-2 MEDIUM #3).
     */
    @Synchronized
    fun initialize(appContext: Context, type: DeviceType) {
        context = appContext.applicationContext
        val previousType = deviceType
        deviceType = type
        cleanOldLogs()
        if (type != DeviceType.UNKNOWN && type != previousType) {
            // #142: detect unclean shutdowns. If a session_state.json marker exists
            // from a previous run, the prior process was killed before reaching
            // shutdownGracefully(). Log that loudly so the next-session-banner is
            // self-contained ("crash recovered from PID=…") and don't lose the data.
            detectAndLogPriorCrash()
            markSessionOpen()
            logSystemInfo()
        }
    }

    /**
     * Called by service onDestroy() paths once teardown completes. Removes the
     * session-open marker so the next session start doesn't false-positive as a
     * crash recovery (#142).
     *
     * Deliberately does NOT shut down [executor]: this is a process-wide singleton and the process
     * can outlive the service that calls this (the user may keep StartActivity open and keep logging),
     * so a shutdown would make every later log() throw RejectedExecutionException. The executor is a
     * daemon thread, so it never blocks a clean process exit (#425).
     */
    fun shutdownGracefully() {
        executor.execute {
            try {
                val ctx = context ?: return@execute
                val marker = File(ctx.filesDir, SESSION_STATE_FILE)
                if (marker.exists()) marker.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear session marker", e)
            }
        }
    }

    private fun detectAndLogPriorCrash() {
        val ctx = context ?: return
        try {
            val marker = File(ctx.filesDir, SESSION_STATE_FILE)
            if (!marker.exists()) return
            val raw = marker.readText().take(2048)
            i(LogCategory.SYSTEM,
                "[CRASH_RECOVERY] Detected unclean shutdown of previous session — marker: $raw")
            // Keep the marker file's data in the log; the actual file is overwritten by
            // markSessionOpen() so subsequent sessions don't redo this work.
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read prior session marker", e)
        }
    }

    private fun markSessionOpen() {
        executor.execute {
            try {
                val ctx = context ?: return@execute
                val marker = File(ctx.filesDir, SESSION_STATE_FILE)
                val payload = """{
                    |"openedAtMs": ${System.currentTimeMillis()},
                    |"deviceType": "${deviceType.name}",
                    |"pid": ${android.os.Process.myPid()},
                    |"appVersion": "${getAppVersion()}"
                    |}""".trimMargin()
                marker.writeText(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write session marker", e)
            }
        }
    }

    /**
     * Log a message with full context
     */
    fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        exception: Throwable? = null
    ) {
        val timestamp = timestampFormat.get()!!.format(Date())
        val logLine = buildString {
            append("[$timestamp] ")
            append("[${level.name}] ")
            append("[${category.name}] ")
            append("[${deviceType.name}] ")
            append(message)
            if (exception != null) {
                append("\n  Exception: ${exception.javaClass.simpleName}: ${exception.message}")
                append("\n  StackTrace: ${exception.stackTraceToString().take(500)}")
            }
        }

        // Also log to Android logcat for debugging
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, logLine)
            LogLevel.INFO -> Log.i(TAG, logLine)
            LogLevel.WARNING -> Log.w(TAG, logLine)
            LogLevel.ERROR -> Log.e(TAG, logLine, exception)
        }

        // Write to file asynchronously
        writeToFile(logLine)
    }

    /**
     * Convenience methods for different log levels
     */
    fun d(category: LogCategory, message: String) {
        // Only log DEBUG messages in debug builds to reduce log volume in production
        if (BuildConfig.DEBUG) {
            log(LogLevel.DEBUG, category, message)
        }
    }
    fun i(category: LogCategory, message: String) = log(LogLevel.INFO, category, message)
    fun w(category: LogCategory, message: String) = log(LogLevel.WARNING, category, message)
    fun e(category: LogCategory, message: String, exception: Throwable? = null) =
        log(LogLevel.ERROR, category, message, exception)

    /**
     * Log connection events with detailed context
     */
    fun logConnection(event: String, ip: String? = null, port: Int? = null, details: String? = null) {
        val msg = buildString {
            append(event)
            // #206: mask the host octet of any IP before it is persisted; the
            // diagnostic log can be exported and shared.
            if (ip != null) append(" | IP: ${LogRedaction.redactIp(ip)}")
            if (port != null) append(":$port")
            if (details != null) append(" | $details")
        }
        i(LogCategory.CONNECTION, msg)
    }

    /**
     * Log network state changes
     */
    fun logNetwork(event: String, ssid: String? = null, signalStrength: Int? = null, details: String? = null) {
        val msg = buildString {
            append(event)
            if (ssid != null) append(" | SSID: $ssid")
            if (signalStrength != null) append(" | Signal: $signalStrength dBm")
            if (details != null) append(" | $details")
        }
        i(LogCategory.NETWORK, msg)
    }

    /**
     * Log audio streaming metrics
     */
    fun logAudio(event: String, bytesTransferred: Long? = null, details: String? = null) {
        val msg = buildString {
            append(event)
            if (bytesTransferred != null) append(" | Bytes: $bytesTransferred")
            if (details != null) append(" | $details")
        }
        d(LogCategory.AUDIO, msg)
    }

    /**
     * Log system information at startup
     */
    private fun logSystemInfo() {
        i(LogCategory.SYSTEM, "=== Logging Session Started ===")
        i(LogCategory.SYSTEM, "Device Type: ${deviceType.name}")
        i(LogCategory.SYSTEM, "Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        i(LogCategory.SYSTEM, "Manufacturer: ${android.os.Build.MANUFACTURER}")
        i(LogCategory.SYSTEM, "Model: ${android.os.Build.MODEL}")
        i(LogCategory.SYSTEM, "App Version: ${getAppVersion()}")
    }

    /**
     * Write log line to today's log file
     */
    private fun writeToFile(logLine: String) {
        executor.execute {
            try {
                val ctx = context ?: run {
                    // #425: logging before initialize() — file write dropped (logcat still emitted by
                    // log()). Surface it once rather than silently swallowing every early line.
                    if (warnedBeforeInit.compareAndSet(false, true)) {
                        Log.w(TAG, "log() called before initialize(); file logging is dropped until initialized")
                    }
                    return@execute
                }
                val logDir = File(ctx.filesDir, LOG_DIR)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }

                val today = dateFormat.get()!!.format(Date())
                val logFile = File(logDir, "log_$today.txt")

                FileWriter(logFile, true).use { writer ->
                    writer.appendLine(logLine)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log to file", e)
            }
        }
    }

    /**
     * Clean up logs older than MAX_LOG_AGE_DAYS
     */
    private fun cleanOldLogs() {
        executor.execute {
            try {
                val ctx = context ?: return@execute
                val logDir = File(ctx.filesDir, LOG_DIR)
                if (!logDir.exists()) return@execute

                val cutoffTime = System.currentTimeMillis() - (MAX_LOG_AGE_DAYS * 24 * 60 * 60 * 1000L)
                val deletedFiles = ArrayList<String>()

                logDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoffTime) {
                        deletedFiles.add(file.name)
                        file.delete()
                    }
                }

                if (deletedFiles.isNotEmpty()) {
                    Log.i(TAG, "Cleaned ${deletedFiles.size} old log files: $deletedFiles")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean old logs", e)
            }
        }
    }

    /**
     * Get all log files sorted by date (newest first)
     */
    fun getLogFiles(): List<File> {
        val ctx = context ?: return emptyList()
        val logDir = File(ctx.filesDir, LOG_DIR)
        if (!logDir.exists()) return emptyList()

        return logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("log_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Get the most recent log file content
     */
    fun getRecentLogs(maxLines: Int = 500): String {
        val logFiles = getLogFiles()
        if (logFiles.isEmpty()) return "No logs available"

        return try {
            val lines = logFiles.first().readLines().takeLast(maxLines)
            lines.joinToString("\n")
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    /**
     * Export all logs as a single string for sharing/analysis
     */
    fun exportAllLogs(): String {
        val logFiles = getLogFiles()
        if (logFiles.isEmpty()) return "No logs available"

        return buildString {
            appendLine("=== Child Monitor Diagnostic Logs ===")
            appendLine("Exported: ${timestampFormat.get()!!.format(Date())}")
            appendLine("Device Type: ${deviceType.name}")
            appendLine("Total Log Files: ${logFiles.size}")
            appendLine()

            logFiles.forEach { file ->
                appendLine("=== ${file.name} ===")
                try {
                    appendLine(file.readText())
                } catch (e: Exception) {
                    appendLine("Error reading file: ${e.message}")
                }
                appendLine()
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            val ctx = context ?: return "Unknown"
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
