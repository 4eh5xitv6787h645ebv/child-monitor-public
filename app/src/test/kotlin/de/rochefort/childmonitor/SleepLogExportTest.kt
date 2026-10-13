package de.rochefort.childmonitor

import de.rochefort.childmonitor.nighttimeline.ExportEvent
import de.rochefort.childmonitor.nighttimeline.MinuteBucket
import de.rochefort.childmonitor.nighttimeline.NightState
import de.rochefort.childmonitor.nighttimeline.SleepLogExport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #309: the sleep/event log export. Pure serialization is unit-tested so the file a parent hands a
 * pediatrician is well-formed, timezone-stable, and safe to open in a spreadsheet (CSV-injection
 * guarded). org.json is on the test classpath (build.gradle testImplementation), so JSON is tested too.
 */
class SleepLogExportTest {

    // 1_700_000_000_000 ms == 2023-11-14T22:13:20Z (UTC), a stable reference.
    private val buckets = listOf(
        MinuteBucket(1_700_000_000_000, rmsMinDbfs = -60.0, rmsMeanDbfs = -50.0, rmsPeakDbfs = -42.0, motion = 5, hasAudio = true, state = NightState.ASLEEP),
        MinuteBucket(1_700_000_060_000, rmsMinDbfs = -30.0, rmsMeanDbfs = -25.0, rmsPeakDbfs = -20.0, motion = 40, hasAudio = true, state = NightState.NOISY),
    )
    private val events = listOf(
        ExportEvent(tsMs = 1_700_000_030_000, type = "cry", confidence = 0.9, durationMs = 3000, peakDb = -12.0),
    )

    @Test
    fun csvHasSleepSectionWithRows() {
        val csv = SleepLogExport.toCsv(buckets, events)
        assertTrue("sleep header", csv.contains("time_iso") && csv.contains("rms_peak_dbfs") && csv.contains("state"))
        assertTrue("sleep state row", csv.contains("ASLEEP") && csv.contains("NOISY"))
        assertTrue("epoch present", csv.contains("1700000000000"))
    }

    @Test
    fun csvHasEventsSection() {
        val csv = SleepLogExport.toCsv(buckets, events)
        assertTrue("events header", csv.contains("event_type"))
        assertTrue("event row", csv.contains("cry"))
    }

    @Test
    fun csvEmptyInputsStillHasBothHeaders() {
        val csv = SleepLogExport.toCsv(emptyList(), emptyList())
        assertTrue(csv.contains("time_iso"))
        assertTrue(csv.contains("event_type"))
    }

    @Test
    fun csvNeutralizesFormulaInjection() {
        // An event type that looks like a spreadsheet formula must be rendered as literal text.
        val evil = listOf(ExportEvent(tsMs = 0, type = "=HYPERLINK(1)", confidence = 0.0, durationMs = 0, peakDb = 0.0))
        val csv = SleepLogExport.toCsv(emptyList(), evil)
        assertTrue("leading = must be quoted + apostrophe-guarded", csv.contains("\"'=HYPERLINK(1)\""))
    }

    @Test
    fun isoTimestampsAreUtcAndStable() {
        val csv = SleepLogExport.toCsv(buckets, emptyList())
        assertTrue("UTC ISO of the reference epoch", csv.contains("2023-11-14T22:13:20Z"))
    }

    @Test
    fun jsonRoundTripsBothDatasetsAndMeta() {
        val json = SleepLogExport.toJson(
            buckets, events,
            generatedAtMs = 123L, windowStartMs = 1_700_000_000_000, windowEndMs = 1_700_000_120_000,
        )
        val o = JSONObject(json)
        assertEquals(2, o.getJSONArray("sleepMinutes").length())
        assertEquals(1, o.getJSONArray("events").length())
        assertEquals("cry", o.getJSONArray("events").getJSONObject(0).getString("type"))
        assertEquals(123L, o.getLong("generatedAtMs"))
        assertEquals(1_700_000_000_000L, o.getLong("windowStartMs"))
    }

    @Test
    fun jsonSleepMinuteHasExpectedFields() {
        val json = SleepLogExport.toJson(buckets, events, 0L, 0L, 0L)
        val m0 = JSONObject(json).getJSONArray("sleepMinutes").getJSONObject(0)
        assertEquals("ASLEEP", m0.getString("state"))
        assertEquals(1_700_000_000_000L, m0.getLong("epochMs"))
        assertEquals(-42.0, m0.getDouble("rmsPeakDbfs"), 0.0001)
        assertEquals(5, m0.getInt("motionPct"))
    }
}
