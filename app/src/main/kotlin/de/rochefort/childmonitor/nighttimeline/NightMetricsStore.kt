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
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.nighttimeline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local per-minute night metrics, SQLite + WAL (mirrors [de.rochefort.childmonitor.events.EventStore]).
 * One row per minute, keyed by the minute epoch so re-emitting a minute (e.g. on flush)
 * overwrites rather than duplicates. Everything is on-device; nothing leaves the phone (#283).
 *
 * Schema: night_minutes(minute_epoch PK, rms_min, rms_mean, rms_peak, motion, state)
 */
class NightMetricsStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS night_minutes(
                minute_epoch INTEGER PRIMARY KEY,
                rms_min REAL NOT NULL,
                rms_mean REAL NOT NULL,
                rms_peak REAL NOT NULL,
                motion INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                has_audio INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // #285: persist the audio-heartbeat flag so render-time reclassification keeps
        // motion-only minutes as NO_SIGNAL instead of recolouring them.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE night_minutes ADD COLUMN has_audio INTEGER NOT NULL DEFAULT 1")
        }
    }

    fun upsert(b: MinuteBucket) {
        val values = ContentValues().apply {
            put("minute_epoch", b.minuteEpochMs)
            put("rms_min", b.rmsMinDbfs)
            put("rms_mean", b.rmsMeanDbfs)
            put("rms_peak", b.rmsPeakDbfs)
            put("motion", b.motion)
            put("state", b.state.name)
            put("has_audio", if (b.hasAudio) 1 else 0)
        }
        // CONFLICT_REPLACE: re-emitting the same minute overwrites the partial row.
        writableDatabase.insertWithOnConflict("night_minutes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** All buckets in [startMs, endMs), oldest first — the timeline render set. */
    fun range(startMs: Long, endMs: Long): List<MinuteBucket> {
        val out = ArrayList<MinuteBucket>()
        readableDatabase.rawQuery(
            "SELECT minute_epoch, rms_min, rms_mean, rms_peak, motion, state, has_audio FROM night_minutes " +
                "WHERE minute_epoch >= ? AND minute_epoch < ? ORDER BY minute_epoch ASC",
            arrayOf(startMs.toString(), endMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MinuteBucket(
                        minuteEpochMs = c.getLong(0),
                        rmsMinDbfs = c.getDouble(1),
                        rmsMeanDbfs = c.getDouble(2),
                        rmsPeakDbfs = c.getDouble(3),
                        motion = c.getInt(4),
                        hasAudio = c.getInt(6) != 0,
                        state = runCatching { NightState.valueOf(c.getString(5)) }.getOrDefault(NightState.NO_SIGNAL)
                    )
                )
            }
        }
        return out
    }

    /** Most recent minute with data, or null if the store is empty. */
    fun latestMinute(): Long? {
        readableDatabase.rawQuery("SELECT MAX(minute_epoch) FROM night_minutes", null).use { c ->
            if (c.moveToNext() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    fun deleteOlderThan(epochMs: Long): Int =
        writableDatabase.delete("night_minutes", "minute_epoch < ?", arrayOf(epochMs.toString()))

    fun clearAll(): Int = writableDatabase.delete("night_minutes", null, null)

    companion object {
        private const val DB_NAME = "night_metrics.db"
        private const val DB_VERSION = 2
        const val RETENTION_DAYS = 30L
    }
}
