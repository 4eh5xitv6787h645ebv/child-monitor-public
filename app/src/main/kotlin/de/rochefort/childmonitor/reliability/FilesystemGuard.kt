package de.rochefort.childmonitor.reliability

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Filesystem pressure backpressure controller for long sessions.
 * Implements CM-REL-008: Filesystem pressure backpressure controller for long sessions.
 *
 * Checks free space on the app's filesDir and signals callers to:
 * - normal: proceed as usual.
 * - degraded: start rotating aggressively and compress logs.
 * - critical: stop optional writes (pcap, media buffer). Safety-critical audio continues.
 */
class FilesystemGuard(private val context: Context) {

    enum class Pressure { NORMAL, DEGRADED, CRITICAL }

    data class Snapshot(
        val pressure: Pressure,
        val freeBytes: Long,
        val totalBytes: Long,
        val appDataBytes: Long
    )

    fun snapshot(): Snapshot {
        val dir = context.filesDir
        val (free, total) = try {
            val stat = StatFs(dir.absolutePath)
            val blockSize = stat.blockSizeLong
            Pair(stat.availableBlocksLong * blockSize, stat.blockCountLong * blockSize)
        } catch (_: Exception) {
            Pair(-1L, -1L)
        }
        val appBytes = dirSizeBytes(dir)
        val pressure = when {
            free < 0 -> Pressure.NORMAL
            free < CRITICAL_BYTES -> Pressure.CRITICAL
            free < DEGRADED_BYTES -> Pressure.DEGRADED
            else -> Pressure.NORMAL
        }
        return Snapshot(pressure, free, total, appBytes)
    }

    private fun dirSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeFirst()
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.addLast(it) }
            } else {
                total += f.length()
            }
        }
        return total
    }

    companion object {
        private const val DEGRADED_BYTES = 500L * 1024L * 1024L  // 500 MB
        private const val CRITICAL_BYTES = 100L * 1024L * 1024L  // 100 MB
    }
}
