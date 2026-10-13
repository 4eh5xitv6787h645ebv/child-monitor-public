package de.rochefort.childmonitor.events

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.rochefort.childmonitor.reliability.MonotonicClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local event timeline store backed by SQLite with WAL journaling.
 * Implements CM-TIME-001 (timeline store + UI) and CM-STORE-003 (WAL + corruption repair).
 *
 * Schema:
 *   events(id INTEGER PK, ts_epoch INTEGER, ts_mono INTEGER,
 *          type TEXT, confidence REAL, duration_ms INTEGER,
 *          peak_db REAL, attrs TEXT, trace_id TEXT)
 *   index(ts_epoch)
 */
class EventStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_epoch INTEGER NOT NULL,
                ts_mono INTEGER NOT NULL,
                type TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                peak_db REAL NOT NULL DEFAULT 0,
                attrs TEXT,
                trace_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts_epoch)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations go here. For now, additive only.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    /**
     * Persist a detector event using an explicit wall-clock timestamp.
     *
     * [SoundEventDetector.Event.startMs] is monotonic elapsed time because the detector uses it
     * to measure region duration. It must never be written into the epoch column (#475).
     */
    fun insertSoundEvent(
        event: SoundEventDetector.Event,
        occurredAtEpochMs: Long,
        traceId: String? = null
    ): Long {
        val values = android.content.ContentValues().apply {
            put("ts_epoch", occurredAtEpochMs)
            put("ts_mono", MonotonicClock.nowMs())
            put("type", event.type)
            put("confidence", event.confidence)
            put("duration_ms", event.durationMs)
            put("peak_db", event.peakDb)
            put("trace_id", traceId)
        }
        return writableDatabase.insert("events", null, values)
    }

    fun insertGeneric(
        type: String,
        confidence: Double,
        durationMs: Long,
        occurredAtEpochMs: Long = System.currentTimeMillis(),
        attrs: Map<String, Any?>? = null,
        traceId: String? = null
    ): Long {
        val values = android.content.ContentValues().apply {
            put("ts_epoch", occurredAtEpochMs)
            put("ts_mono", MonotonicClock.nowMs())
            put("type", type)
            put("confidence", confidence)
            put("duration_ms", durationMs)
            put("peak_db", 0.0)
            put("attrs", if (attrs != null) JSONObject(attrs).toString() else null)
            put("trace_id", traceId)
        }
        return writableDatabase.insert("events", null, values)
    }

    fun recent(limit: Int = 200): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, ts_epoch, type, confidence, duration_ms, peak_db, attrs, trace_id FROM events ORDER BY ts_epoch DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("tsMs", c.getLong(1))
                        .put("type", c.getString(2))
                        .put("confidence", c.getDouble(3))
                        .put("durationMs", c.getLong(4))
                        .put("peakDb", c.getDouble(5))
                        .put("attrs", c.getString(6))
                        .put("traceId", c.getString(7))
                )
            }
        }
        return out
    }

    /** #309: events in [startMs, endMs] (both bounds INCLUSIVE — note NightMetricsStore.range is
     *  upper-exclusive), oldest first — for the sleep/event log export. */
    fun range(startMs: Long, endMs: Long): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT ts_epoch, type, confidence, duration_ms, peak_db FROM events " +
                "WHERE ts_epoch >= ? AND ts_epoch <= ? ORDER BY ts_epoch ASC",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("tsMs", c.getLong(0))
                        .put("type", c.getString(1))
                        .put("confidence", c.getDouble(2))
                        .put("durationMs", c.getLong(3))
                        .put("peakDb", c.getDouble(4))
                )
            }
        }
        return out
    }

    fun deleteOlderThan(epochMs: Long): Int {
        return writableDatabase.delete("events", "ts_epoch < ?", arrayOf(epochMs.toString()))
    }

    fun integritySelfTest(): Boolean {
        return try {
            readableDatabase.rawQuery("PRAGMA integrity_check", null).use { c ->
                c.moveToNext() && c.getString(0).equals("ok", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val DB_NAME = "events.db"
        private const val DB_VERSION = 1
    }
}
