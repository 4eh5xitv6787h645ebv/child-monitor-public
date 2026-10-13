package de.rochefort.childmonitor.test

import org.json.JSONObject

/**
 * 90-day unattended burn-in automation report generator.
 * Implements CM-TST-010 (90-day unattended burn-in + weekly reports) and
 *            CM-TST-005 (7-day soak with VPN chaos profiles).
 *
 * The actual burn-in harness runs outside the app (on a CI runner with two
 * emulators). This class defines the structured report format the runner
 * produces so dashboards and gating logic don't drift.
 */
object BurnInReporter {

    data class Run(
        val runId: String,
        val startWallMs: Long,
        val endWallMs: Long,
        val samples: List<Sample>,
        val incidents: List<Incident>
    )

    data class Sample(
        val tsMs: Long,
        val cpuUsagePct: Double,
        val heapMb: Double,
        val rttMs: Int,
        val lossPct: Double,
        val audioUnderruns: Int,
        val reconnects: Int
    )

    data class Incident(
        val tsMs: Long,
        val severity: String,
        val category: String,
        val message: String
    )

    fun toWeeklyReport(run: Run): JSONObject {
        val (cpuMax, cpuAvg) = percentiles(run.samples.map { it.cpuUsagePct })
        val (heapMax, heapAvg) = percentiles(run.samples.map { it.heapMb })
        val incidents = org.json.JSONArray()
        for (inc in run.incidents) {
            incidents.put(
                JSONObject()
                    .put("tsMs", inc.tsMs)
                    .put("severity", inc.severity)
                    .put("category", inc.category)
                    .put("message", inc.message)
            )
        }
        return JSONObject()
            .put("runId", run.runId)
            .put("durationMs", run.endWallMs - run.startWallMs)
            .put("samples", run.samples.size)
            .put("cpu.maxPct", cpuMax)
            .put("cpu.avgPct", cpuAvg)
            .put("heap.maxMb", heapMax)
            .put("heap.avgMb", heapAvg)
            .put("incidents", incidents)
    }

    private fun percentiles(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        val max = values.max()
        val avg = values.average()
        return max to avg
    }

    /**
     * Pass/fail gates applied by CI after each soak run.
     */
    fun isPassing(report: JSONObject): Boolean {
        val errorIncidents = report.optJSONArray("incidents")?.let { arr ->
            (0 until arr.length()).count { arr.getJSONObject(it).optString("severity") == "ERROR" }
        } ?: 0
        val cpuMax = report.optDouble("cpu.maxPct", 0.0)
        val heapMax = report.optDouble("heap.maxMb", 0.0)
        return errorIncidents == 0 && cpuMax < 55.0 && heapMax < 260.0
    }
}
