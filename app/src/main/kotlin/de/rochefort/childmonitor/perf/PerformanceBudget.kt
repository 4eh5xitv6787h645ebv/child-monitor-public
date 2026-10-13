package de.rochefort.childmonitor.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import de.rochefort.childmonitor.reliability.MonotonicClock
import org.json.JSONObject

/**
 * Performance budgets and regression alerts (CPU/memory/battery).
 * Implements CM-PERF-001 (budgets) and CM-PERF-002 (startup latency).
 *
 * Budgets are expressed as simple JSON thresholds stored alongside metrics.
 * When the caller observes a measurement that exceeds a threshold, it records
 * a "regression" attributes bag that tests / dashboards can gate on.
 */
object PerformanceBudget {

    data class Budget(
        val name: String,
        val warningThreshold: Double,
        val errorThreshold: Double,
        val unit: String
    )

    val STARTUP_LATENCY_MS = Budget("startup_latency_ms", 2_000.0, 4_000.0, "ms")
    val HEAP_MB = Budget("heap_mb", 180.0, 260.0, "MiB")
    val CPU_USAGE_PCT = Budget("cpu_usage_pct", 30.0, 55.0, "%")
    val AUDIO_UNDERRUN_PER_MIN = Budget("audio_underruns_per_min", 2.0, 10.0, "count")

    data class Assessment(
        val budget: Budget,
        val observed: Double,
        val severity: Severity
    ) {
        enum class Severity { OK, WARNING, ERROR }

        fun toJson(): JSONObject = JSONObject()
            .put("name", budget.name)
            .put("observed", observed)
            .put("warning", budget.warningThreshold)
            .put("error", budget.errorThreshold)
            .put("unit", budget.unit)
            .put("severity", severity.name)
    }

    fun evaluate(budget: Budget, observed: Double): Assessment {
        val severity = when {
            observed >= budget.errorThreshold -> Assessment.Severity.ERROR
            observed >= budget.warningThreshold -> Assessment.Severity.WARNING
            else -> Assessment.Severity.OK
        }
        return Assessment(budget, observed, severity)
    }

    /** Snapshots process-level performance indicators. */
    fun snapshot(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val pidMemory = try {
            val pid = android.os.Process.myPid()
            val info = am?.getProcessMemoryInfo(intArrayOf(pid))?.firstOrNull()
            info?.totalPss?.toLong() ?: 0L
        } catch (_: Exception) { 0L }

        val heap = Runtime.getRuntime().let {
            (it.totalMemory() - it.freeMemory()).toDouble() / (1024.0 * 1024.0)
        }
        return JSONObject()
            .put("pssKb", pidMemory)
            .put("availMem", memInfo.availMem)
            .put("totalMem", memInfo.totalMem)
            .put("lowMemory", memInfo.lowMemory)
            .put("threshold", memInfo.threshold)
            .put("heapMb", heap)
            .put("tsMonoMs", MonotonicClock.nowMs())
    }
}
