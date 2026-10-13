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
 */
package de.rochefort.childmonitor.nighttimeline

import de.rochefort.childmonitor.util.CsvSafe
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A detected sound event flattened for export (decoupled from EventStore's JSON row shape). */
data class ExportEvent(
    val tsMs: Long,
    val type: String,
    val confidence: Double,
    val durationMs: Long,
    val peakDb: Double,
)

/**
 * #309: serialize the local sleep timeline ([MinuteBucket]) and detected sound [ExportEvent]s to a
 * file a parent can hand a pediatrician / sleep consultant. Pure (no Android, no I/O) so it is
 * unit-tested; the Activity does the DB reads, file write, and FileProvider share.
 *
 * Both formats carry both datasets. Timestamps are emitted as UTC ISO-8601 (locale/timezone-stable
 * for a shared file) alongside the raw epoch millis. CSV cells are RFC4180-quoted and CSV-injection
 * guarded so opening the file in Excel / Sheets can't execute a formula.
 */
object SleepLogExport {

    private const val SLEEP_HEADER =
        "time_iso,epoch_ms,state,rms_min_dbfs,rms_mean_dbfs,rms_peak_dbfs,motion_pct,has_audio"
    private const val EVENTS_HEADER =
        "time_iso,epoch_ms,event_type,confidence,duration_ms,peak_db"

    fun toCsv(buckets: List<MinuteBucket>, events: List<ExportEvent>): String {
        val iso = utcIsoFormatter()
        val sb = StringBuilder()
        sb.append("# Sleep timeline (one row per minute)\n")
        sb.append(SLEEP_HEADER).append('\n')
        for (b in buckets) {
            sb.append(CsvSafe.cell(iso.format(Date(b.minuteEpochMs)))).append(',')
            sb.append(b.minuteEpochMs).append(',')
            sb.append(CsvSafe.cell(b.state.name)).append(',')
            sb.append(b.rmsMinDbfs).append(',')
            sb.append(b.rmsMeanDbfs).append(',')
            sb.append(b.rmsPeakDbfs).append(',')
            sb.append(b.motion).append(',')
            sb.append(if (b.hasAudio) 1 else 0).append('\n')
        }
        sb.append('\n')
        sb.append("# Events (detected sounds)\n")
        sb.append(EVENTS_HEADER).append('\n')
        for (e in events) {
            sb.append(CsvSafe.cell(iso.format(Date(e.tsMs)))).append(',')
            sb.append(e.tsMs).append(',')
            sb.append(CsvSafe.cell(e.type)).append(',')
            sb.append(e.confidence).append(',')
            sb.append(e.durationMs).append(',')
            sb.append(e.peakDb).append('\n')
        }
        return sb.toString()
    }

    fun toJson(
        buckets: List<MinuteBucket>,
        events: List<ExportEvent>,
        generatedAtMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): String {
        val iso = utcIsoFormatter()
        val sleep = JSONArray()
        for (b in buckets) {
            sleep.put(
                JSONObject()
                    .put("epochMs", b.minuteEpochMs)
                    .put("timeIso", iso.format(Date(b.minuteEpochMs)))
                    .put("state", b.state.name)
                    .put("rmsMinDbfs", b.rmsMinDbfs)
                    .put("rmsMeanDbfs", b.rmsMeanDbfs)
                    .put("rmsPeakDbfs", b.rmsPeakDbfs)
                    .put("motionPct", b.motion)
                    .put("hasAudio", b.hasAudio)
            )
        }
        val evs = JSONArray()
        for (e in events) {
            evs.put(
                JSONObject()
                    .put("tsMs", e.tsMs)
                    .put("timeIso", iso.format(Date(e.tsMs)))
                    .put("type", e.type)
                    .put("confidence", e.confidence)
                    .put("durationMs", e.durationMs)
                    .put("peakDb", e.peakDb)
            )
        }
        return JSONObject()
            .put("schema", "child-monitor.sleep-log.v1")
            .put("generatedAtMs", generatedAtMs)
            .put("windowStartMs", windowStartMs)
            .put("windowEndMs", windowEndMs)
            .put("sleepMinutes", sleep)
            .put("events", evs)
            .toString(2)
    }

    private fun utcIsoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

}
