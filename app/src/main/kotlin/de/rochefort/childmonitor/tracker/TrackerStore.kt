package de.rochefort.childmonitor.tracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.rochefort.childmonitor.reliability.MonotonicClock
import org.json.JSONObject

/**
 * SQLite-backed store for tracker events. Separate database from `events.db`
 * (the on-device monitor event timeline) so we can ship a clean schema
 * without migrating legacy rows.
 *
 * Schema is additive-only; future changes add columns or new tables.
 */
class TrackerStore private constructor(appContext: Context) :
    SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tracker_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                subkind TEXT,
                ts_wall_ms INTEGER NOT NULL,
                ts_mono_ms INTEGER NOT NULL,
                started_ms INTEGER,
                ended_ms INTEGER,
                value_num REAL,
                value_num2 REAL,
                extra_json TEXT,
                source TEXT NOT NULL DEFAULT 'USER_ENTRY',
                note TEXT,
                trace_id TEXT,
                deleted_at_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracker_child_ts ON tracker_events(child_id, ts_wall_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracker_child_kind ON tracker_events(child_id, kind, ts_wall_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracker_deleted_at ON tracker_events(deleted_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracker_events ADD COLUMN deleted_at_ms INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracker_deleted_at ON tracker_events(deleted_at_ms)")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    fun insert(event: TrackerEvent): Long {
        val cv = contentValuesForInsert(event)
        // Never turn a failed write into a plausible row id. UI callers surface this exception and
        // retain the caregiver's input instead of showing a false success (#459).
        return writableDatabase.insertOrThrow("tracker_events", null, cv)
    }

    /**
     * #192-3: atomic multi-row insert. Used by callers like the medication
     * entry path that record a TEMPERATURE and a MEDICATION row together —
     * if the process is killed between the two individual inserts, the temp
     * persists but the medication row is lost, and the parent's next dose
     * attempt can exceed the 24h cap because the cap calculation only sees
     * the surviving temperature row. wrapping in a SQLite transaction makes
     * both rows commit (or neither).
     *
     * Returns the rowids in the same order as [events]; throws on any failure
     * (the whole transaction rolls back).
     */
    fun insertAll(events: List<TrackerEvent>): List<Long> {
        if (events.isEmpty()) return emptyList()
        val db = writableDatabase
        val ids = ArrayList<Long>(events.size)
        db.beginTransaction()
        try {
            for (event in events) {
                // Use insertOrThrow so a constraint failure (-1 return on
                // .insert) actually aborts the transaction. The previous
                // version silently committed surviving rows, undermining the
                // whole point of insertAll — the MEDICATION row could fail
                // while the TEMPERATURE row committed, leaving the parent
                // believing the dose was logged.
                ids.add(db.insertOrThrow("tracker_events", null, contentValuesForInsert(event)))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    private fun contentValuesForInsert(event: TrackerEvent): ContentValues {
        // Stamp device timezone into extra_json without mutating the caller's
        // JSONObject — rebuild from its serialised form so immutability rules
        // are respected (CM-TRK-REV-019 + CM-TRK-CR2-P1-2).
        val extraWithTz = JSONObject(event.extra?.toString() ?: "{}").also {
            if (!it.has("tz")) it.put("tz", java.util.TimeZone.getDefault().id)
            if (!it.has("tzOffsetMin")) {
                val offsetMin = java.util.TimeZone.getDefault().getOffset(event.tsWallMs) / 60_000
                it.put("tzOffsetMin", offsetMin)
            }
        }
        return ContentValues().apply {
            put("child_id", event.childId)
            put("kind", event.kind.name)
            put("subkind", event.subkind)
            put("ts_wall_ms", event.tsWallMs)
            put("ts_mono_ms", event.tsMonoMs)
            put("started_ms", event.startedMs)
            put("ended_ms", event.endedMs)
            put("value_num", event.valueNum)
            put("value_num2", event.valueNum2)
            put("extra_json", extraWithTz.toString())
            put("source", event.source.name)
            put("note", event.note)
            put("trace_id", event.traceId)
        }
    }

    fun recent(childId: String, limit: Int = 200): List<TrackerEvent> = query(
        "SELECT * FROM tracker_events WHERE child_id = ? AND deleted_at_ms IS NULL ORDER BY ts_wall_ms DESC LIMIT ?",
        arrayOf(childId, limit.toString())
    )

    fun recentlyDeleted(childId: String, deletedAfterMs: Long, limit: Int = 200): List<TrackerEvent> = query(
        "SELECT * FROM tracker_events " +
            "WHERE child_id = ? AND deleted_at_ms IS NOT NULL AND deleted_at_ms > ? " +
            "ORDER BY deleted_at_ms DESC LIMIT ?",
        arrayOf(childId, deletedAfterMs.toString(), limit.toString()),
    )

    fun recentByKind(childId: String, kind: TrackerEvent.Kind, limit: Int = 50): List<TrackerEvent> = query(
        "SELECT * FROM tracker_events WHERE child_id = ? AND kind = ? AND deleted_at_ms IS NULL ORDER BY ts_wall_ms DESC LIMIT ?",
        arrayOf(childId, kind.name, limit.toString())
    )

    fun since(childId: String, sinceWallMs: Long, kinds: Set<TrackerEvent.Kind> = emptySet()): List<TrackerEvent> {
        val baseSql = "SELECT * FROM tracker_events WHERE child_id = ? AND ts_wall_ms >= ? AND deleted_at_ms IS NULL"
        return if (kinds.isEmpty()) {
            query("$baseSql ORDER BY ts_wall_ms DESC", arrayOf(childId, sinceWallMs.toString()))
        } else {
            val placeholders = kinds.joinToString(",") { "?" }
            val args = arrayOf(childId, sinceWallMs.toString()) + kinds.map { it.name }.toTypedArray()
            query("$baseSql AND kind IN ($placeholders) ORDER BY ts_wall_ms DESC", args)
        }
    }

    fun lastByKind(childId: String, kind: TrackerEvent.Kind): TrackerEvent? = query(
        "SELECT * FROM tracker_events WHERE child_id = ? AND kind = ? AND deleted_at_ms IS NULL ORDER BY ts_wall_ms DESC LIMIT 1",
        arrayOf(childId, kind.name)
    ).firstOrNull()

    /**
     * #147-2: most recent event of a specific kind + subkind for one child.
     * Used by the medication calculator to fetch the last paracetamol /
     * ibuprofen dose for the cross-drug interleave warning.
     */
    fun lastByKindSubkind(childId: String, kind: TrackerEvent.Kind, subkind: String): TrackerEvent? = query(
        "SELECT * FROM tracker_events WHERE child_id = ? AND kind = ? AND subkind = ? AND deleted_at_ms IS NULL ORDER BY ts_wall_ms DESC LIMIT 1",
        arrayOf(childId, kind.name, subkind)
    ).firstOrNull()

    fun countSince(childId: String, kind: TrackerEvent.Kind, sinceWallMs: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracker_events WHERE child_id = ? AND kind = ? AND ts_wall_ms >= ? AND deleted_at_ms IS NULL",
            arrayOf(childId, kind.name, sinceWallMs.toString())
        ).use { c ->
            return if (c.moveToNext()) c.getInt(0) else 0
        }
    }

    /**
     * Sum `value_num` across rows for one kind+subkind since a timestamp.
     * Used by the medication calculator for the 24h cap — avoids the LIMIT-based
     * in-memory filter that silently undercounts on busy days.
     */
    fun sumValueSince(
        childId: String,
        kind: TrackerEvent.Kind,
        subkind: String,
        sinceWallMs: Long
    ): DoseSummary {
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(value_num), 0.0), COUNT(*), MAX(ts_wall_ms)
            FROM tracker_events
            WHERE child_id = ? AND kind = ? AND subkind = ? AND ts_wall_ms >= ?
              AND deleted_at_ms IS NULL
            """.trimIndent(),
            arrayOf(childId, kind.name, subkind, sinceWallMs.toString())
        ).use { c ->
            if (!c.moveToNext()) return DoseSummary(0.0, 0, null)
            val totalMg = c.getDouble(0)
            val count = c.getInt(1)
            val lastMs = if (c.isNull(2)) null else c.getLong(2)
            return DoseSummary(totalMg, count, lastMs)
        }
    }

    data class DoseSummary(val totalValue: Double, val count: Int, val lastAtMs: Long?)

    /** Return one active row only when both its id and owning child match. */
    fun find(childId: String, id: Long): TrackerEvent? = query(
        "SELECT * FROM tracker_events WHERE id = ? AND child_id = ? AND deleted_at_ms IS NULL LIMIT 1",
        arrayOf(id.toString(), childId),
    ).firstOrNull()

    /**
     * Atomically replace every caregiver-editable field without allowing an event to move between
     * children. The original id, source, and trace metadata remain attached to the same audit row.
     */
    fun replace(childId: String, event: TrackerEvent): Int {
        require(event.id > 0) { "an existing tracker row id is required" }
        require(event.childId == childId) { "tracker event belongs to a different child" }
        val cv = contentValuesForInsert(event).apply {
            remove("child_id")
            remove("source")
            remove("trace_id")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT * FROM tracker_events WHERE id = ? AND child_id = ? AND deleted_at_ms IS NULL LIMIT 1",
                arrayOf(event.id.toString(), childId),
            ).use { cursor -> if (cursor.moveToFirst()) cursorToEvent(cursor) else null } ?: return 0
            require(previous.kind == event.kind) { "tracker event type cannot be changed in place" }
            if (event.kind in setOf(TrackerEvent.Kind.SLEEP_START, TrackerEvent.Kind.NAP_START)) {
                val start = event.startedMs ?: event.tsWallMs
                require(event.endedMs == null || start <= event.endedMs) {
                    "sleep start cannot be after its end"
                }
            }
            if (event.kind in setOf(TrackerEvent.Kind.SLEEP_END, TrackerEvent.Kind.NAP_END)) {
                val end = event.endedMs ?: event.tsWallMs
                require(event.startedMs == null || event.startedMs <= end) {
                    "sleep end cannot be before its start"
                }
            }
            val changed = db.update(
                "tracker_events",
                cv,
                "id = ? AND child_id = ? AND deleted_at_ms IS NULL",
                arrayOf(event.id.toString(), childId),
            )
            if (changed == 1) synchronizeLinkedSleepRow(db, previous, event)
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    /** Keep the two-row sleep representation coherent when either timeline timestamp is edited. */
    private fun synchronizeLinkedSleepRow(
        db: SQLiteDatabase,
        previous: TrackerEvent,
        updated: TrackerEvent,
    ) {
        val oldStartedMs = previous.startedMs ?: return
        when (previous.kind) {
            TrackerEvent.Kind.SLEEP_START, TrackerEvent.Kind.NAP_START -> {
                if (previous.endedMs == null) return
                val endKind = if (previous.kind == TrackerEvent.Kind.NAP_START) {
                    TrackerEvent.Kind.NAP_END
                } else {
                    TrackerEvent.Kind.SLEEP_END
                }
                val newStartedMs = updated.startedMs ?: updated.tsWallMs
                db.rawQuery(
                    "SELECT id, ended_ms FROM tracker_events " +
                        "WHERE child_id = ? AND kind = ? AND started_ms = ? " +
                        "AND deleted_at_ms IS NULL ORDER BY id DESC LIMIT 1",
                    arrayOf(previous.childId, endKind.name, oldStartedMs.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val endId = cursor.getLong(0)
                        val endedMs = if (cursor.isNull(1)) null else cursor.getLong(1)
                        db.update(
                            "tracker_events",
                            ContentValues().apply {
                                put("started_ms", newStartedMs)
                                if (endedMs != null) put("value_num", (endedMs - newStartedMs) / 60_000.0)
                                if (updated.subkind == null) putNull("subkind") else put("subkind", updated.subkind)
                            },
                            "id = ? AND child_id = ? AND deleted_at_ms IS NULL",
                            arrayOf(endId.toString(), previous.childId),
                        )
                    }
                }
            }
            TrackerEvent.Kind.SLEEP_END, TrackerEvent.Kind.NAP_END -> {
                val startKind = if (previous.kind == TrackerEvent.Kind.NAP_END) {
                    TrackerEvent.Kind.NAP_START
                } else {
                    TrackerEvent.Kind.SLEEP_START
                }
                val newEndedMs = updated.endedMs ?: updated.tsWallMs
                db.update(
                    "tracker_events",
                    ContentValues().apply {
                        put("ended_ms", newEndedMs)
                        if (updated.subkind == null) putNull("subkind") else put("subkind", updated.subkind)
                    },
                    "child_id = ? AND kind = ? AND started_ms = ? AND deleted_at_ms IS NULL",
                    arrayOf(previous.childId, startKind.name, oldStartedMs.toString()),
                )
            }
            else -> Unit
        }
    }

    /** Keep deleted tracker rows for undo/audit recovery instead of destroying health history. */
    fun softDelete(childId: String, id: Long, deletedAtMs: Long = System.currentTimeMillis()): Int =
        writableDatabase.update(
            "tracker_events",
            ContentValues().apply { put("deleted_at_ms", deletedAtMs) },
            "id = ? AND child_id = ? AND deleted_at_ms IS NULL",
            arrayOf(id.toString(), childId),
        )

    fun restore(childId: String, id: Long): Int =
        writableDatabase.update(
            "tracker_events",
            ContentValues().apply { putNull("deleted_at_ms") },
            "id = ? AND child_id = ? AND deleted_at_ms IS NOT NULL",
            arrayOf(id.toString(), childId),
        )

    /** Soft-delete a logical multi-row save as one all-or-nothing operation. */
    fun softDeleteAll(
        childId: String,
        ids: Collection<Long>,
        deletedAtMs: Long = System.currentTimeMillis(),
    ): Int = changeDeletionState(childId, ids, deletedAtMs)

    fun restoreAll(childId: String, ids: Collection<Long>): Int =
        changeDeletionState(childId, ids, deletedAtMs = null)

    private fun changeDeletionState(childId: String, ids: Collection<Long>, deletedAtMs: Long?): Int {
        val uniqueIds = ids.distinct()
        if (uniqueIds.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in uniqueIds) {
                val changed = db.update(
                    "tracker_events",
                    ContentValues().apply {
                        if (deletedAtMs == null) putNull("deleted_at_ms") else put("deleted_at_ms", deletedAtMs)
                    },
                    if (deletedAtMs == null) {
                        "id = ? AND child_id = ? AND deleted_at_ms IS NOT NULL"
                    } else {
                        "id = ? AND child_id = ? AND deleted_at_ms IS NULL"
                    },
                    arrayOf(id.toString(), childId),
                )
                check(changed == 1) { "tracker event was not found" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return uniqueIds.size
    }

    /** Permanently remove only tombstones whose 30-day recovery period has expired. */
    fun purgeDeletedBefore(cutoffWallMs: Long): Int = writableDatabase.delete(
        "tracker_events",
        "deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?",
        arrayOf(cutoffWallMs.toString()),
    )

    /** Atomically append the end marker and close its matching start row. */
    fun finishSleep(start: TrackerEvent, end: TrackerEvent, endedMs: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val endId = db.insertOrThrow("tracker_events", null, contentValuesForInsert(end))
            val changed = db.update(
                "tracker_events",
                ContentValues().apply { put("ended_ms", endedMs) },
                "id = ? AND child_id = ? AND ended_ms IS NULL AND deleted_at_ms IS NULL",
                arrayOf(start.id.toString(), start.childId),
            )
            check(changed == 1) { "sleep start was already closed or missing" }
            db.setTransactionSuccessful()
            return endId
        } finally {
            db.endTransaction()
        }
    }

    /** Reverse [finishSleep] atomically during the five-second undo window. */
    fun undoFinishSleep(childId: String, startId: Long, endId: Long, deletedAtMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val reopened = db.update(
                "tracker_events",
                ContentValues().apply { putNull("ended_ms") },
                "id = ? AND child_id = ? AND deleted_at_ms IS NULL",
                arrayOf(startId.toString(), childId),
            )
            check(reopened == 1) { "sleep start was not found" }
            val removedEnd = db.update(
                "tracker_events",
                ContentValues().apply { put("deleted_at_ms", deletedAtMs) },
                "id = ? AND child_id = ? AND deleted_at_ms IS NULL",
                arrayOf(endId.toString(), childId),
            )
            check(removedEnd == 1) { "sleep end was not found" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Delete all rows for [childId]. Used when a profile is removed so old
     *  events don't leak into a new child that happens to reuse the UUID. */
    fun deleteAllForChild(childId: String): Int =
        writableDatabase.delete("tracker_events", "child_id = ?", arrayOf(childId))

    private fun query(sql: String, args: Array<String>): List<TrackerEvent> {
        val out = mutableListOf<TrackerEvent>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(cursorToEvent(c))
        }
        return out
    }

    private fun cursorToEvent(c: android.database.Cursor): TrackerEvent {
        fun idx(name: String) = c.getColumnIndexOrThrow(name)
        val extraStr = c.getString(idx("extra_json"))
        val extra = extraStr?.let { runCatching { JSONObject(it) }.getOrNull() }
        return TrackerEvent(
            id = c.getLong(idx("id")),
            childId = c.getString(idx("child_id")),
            kind = runCatching { TrackerEvent.Kind.valueOf(c.getString(idx("kind"))) }.getOrDefault(TrackerEvent.Kind.NOTE),
            subkind = c.getString(idx("subkind")),
            tsWallMs = c.getLong(idx("ts_wall_ms")),
            tsMonoMs = c.getLong(idx("ts_mono_ms")),
            startedMs = if (c.isNull(idx("started_ms"))) null else c.getLong(idx("started_ms")),
            endedMs = if (c.isNull(idx("ended_ms"))) null else c.getLong(idx("ended_ms")),
            valueNum = if (c.isNull(idx("value_num"))) null else c.getDouble(idx("value_num")),
            valueNum2 = if (c.isNull(idx("value_num2"))) null else c.getDouble(idx("value_num2")),
            extra = extra,
            source = runCatching { TrackerEvent.Source.valueOf(c.getString(idx("source"))) }.getOrDefault(TrackerEvent.Source.USER_ENTRY),
            note = c.getString(idx("note")),
            traceId = c.getString(idx("trace_id"))
        )
    }

    companion object {
        private const val DB_NAME = "tracker.db"
        private const val DB_VERSION = 2

        @Volatile private var INSTANCE: TrackerStore? = null

        /**
         * Returns the process-wide singleton. Uses the application context so we
         * never leak an Activity. Multiple activities calling this must not close
         * the handle; the helper stays open for the process lifetime.
         */
        fun get(context: Context): TrackerStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackerStore(context.applicationContext).also { INSTANCE = it }
            }

        /** Escape hatch for tests. Call only on teardown. */
        internal fun resetForTest() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun nowMono(): Long = MonotonicClock.nowMs()
    }
}
