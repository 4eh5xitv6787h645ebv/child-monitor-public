package de.rochefort.childmonitor.tracker

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.ui.BabyDashboardActivity
import de.rochefort.childmonitor.tracker.ui.FeedEntryActivity
import de.rochefort.childmonitor.tracker.ui.TrackerEventEditActivity
import de.rochefort.childmonitor.tracker.ui.TrackerEventLabels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TrackerStore.resetForTest()
        ChildProfileStore.resetForTest()
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        runCatching {
            context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("DROP TRIGGER IF EXISTS fail_tracker_insert")
                db.execSQL("DROP TRIGGER IF EXISTS fail_growth_length")
            }
        }
        TrackerStore.resetForTest()
        ChildProfileStore.resetForTest()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun realSQLiteRoundTripPreservesCommittedEvent() {
        val store = TrackerStore.get(context)
        val event = TrackerEvent(
            childId = "child-roundtrip",
            kind = TrackerEvent.Kind.FEED_BOTTLE,
            valueNum = 125.5,
            note = "instrumented",
        )

        val id = store.insert(event)
        val loaded = store.recent("child-roundtrip")

        assertTrue(id > 0)
        assertEquals(1, loaded.size)
        assertEquals(125.5, loaded.single().valueNum ?: 0.0, 0.0)
        assertEquals("instrumented", loaded.single().note)
    }

    @Test
    fun editSoftDeleteRestorePurgeArePersistentAndChildScoped() {
        val store = TrackerStore.get(context)
        val childId = "child-edit"
        val id = store.insert(
            TrackerEvent(
                childId = childId,
                kind = TrackerEvent.Kind.FEED_BOTTLE,
                subkind = TrackerEvent.FeedSubkind.BOTTLE_EBM,
                valueNum = 90.0,
                note = "typo",
            )
        )
        val original = requireNotNull(store.find(childId, id))

        assertEquals(
            1,
            store.replace(
                childId,
                original.copy(
                    subkind = TrackerEvent.FeedSubkind.BOTTLE_FORMULA,
                    valueNum = 12.5,
                    note = "corrected",
                ),
            ),
        )
        assertEquals("corrected", store.find(childId, id)?.note)
        assertEquals(TrackerEvent.FeedSubkind.BOTTLE_FORMULA, store.find(childId, id)?.subkind)
        val typeChangeFailure = runCatching {
            store.replace(
                childId,
                requireNotNull(store.find(childId, id)).copy(kind = TrackerEvent.Kind.MEDICATION),
            )
        }.exceptionOrNull()
        assertTrue(typeChangeFailure is IllegalArgumentException)
        assertEquals(TrackerEvent.Kind.FEED_BOTTLE, store.find(childId, id)?.kind)
        assertEquals(0, store.replace("another-child", original.copy(childId = "another-child")))
        assertEquals(0, store.softDelete("another-child", id, deletedAtMs = 100L))

        assertEquals(1, store.softDelete(childId, id, deletedAtMs = 100L))
        assertNull(store.find(childId, id))
        assertTrue(store.recent(childId).isEmpty())
        assertEquals(listOf(id), store.recentlyDeleted(childId, deletedAfterMs = 99L).map { it.id })
        assertTrue(store.recentlyDeleted(childId, deletedAfterMs = 100L).isEmpty())

        TrackerStore.resetForTest()
        val reopened = TrackerStore.get(context)
        assertTrue(reopened.recent(childId).isEmpty())
        assertEquals(1, reopened.restore(childId, id))
        assertEquals("corrected", reopened.find(childId, id)?.note)

        assertEquals(1, reopened.softDelete(childId, id, deletedAtMs = 100L))
        assertEquals(0, reopened.purgeDeletedBefore(99L))
        assertEquals(1, reopened.purgeDeletedBefore(100L))
        assertEquals(0, reopened.restore(childId, id))
    }

    @Test
    fun multiRowDeleteAndRestoreAreAtomic() {
        val store = TrackerStore.get(context)
        val childId = "child-multi-undo"
        val ids = store.insertAll(
            listOf(
                TrackerEvent(childId = childId, kind = TrackerEvent.Kind.GROWTH_WEIGHT, valueNum = 5.0),
                TrackerEvent(childId = childId, kind = TrackerEvent.Kind.GROWTH_LENGTH, valueNum = 60.0),
            )
        )

        val failed = runCatching { store.softDeleteAll(childId, ids + 999_999L) }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
        assertEquals(2, store.recent(childId).size)

        assertEquals(2, store.softDeleteAll(childId, ids))
        assertTrue(store.recent(childId).isEmpty())
        assertEquals(2, store.restoreAll(childId, ids))
        assertEquals(2, store.recent(childId).size)
    }

    @Test
    fun finishSleepUndoReopensStartAndHidesEndAtomically() {
        val store = TrackerStore.get(context)
        val childId = "child-sleep-undo"
        val startedAt = System.currentTimeMillis() - 30L * 60_000L
        val startId = store.insert(
            TrackerEvent(
                childId = childId,
                kind = TrackerEvent.Kind.NAP_START,
                tsWallMs = startedAt,
                startedMs = startedAt,
            )
        )
        val start = requireNotNull(store.find(childId, startId))
        // Keep the intended 30-minute interval exact. A second wall-clock read makes
        // this assertion depend on emulator speed and previously failed on API 36.
        val endedAt = startedAt + 30L * 60_000L
        val endId = store.finishSleep(
            start,
            TrackerEvent(
                childId = childId,
                kind = TrackerEvent.Kind.NAP_END,
                startedMs = startedAt,
                endedMs = endedAt,
            ),
            endedAt,
        )
        assertEquals(endedAt, store.find(childId, startId)?.endedMs)
        assertEquals(2, store.recent(childId).size)

        val shiftedStartMs = startedAt + 5L * 60_000L
        val currentStart = requireNotNull(store.find(childId, startId))
        assertEquals(
            1,
            store.replace(
                childId,
                currentStart.copy(tsWallMs = shiftedStartMs, startedMs = shiftedStartMs),
            ),
        )
        var currentEnd = requireNotNull(store.find(childId, endId))
        assertEquals(shiftedStartMs, currentEnd.startedMs)
        assertEquals(25.0, currentEnd.valueNum ?: -1.0, 0.01)

        val shiftedEndMs = endedAt + 10L * 60_000L
        assertEquals(
            1,
            store.replace(
                childId,
                currentEnd.copy(tsWallMs = shiftedEndMs, endedMs = shiftedEndMs, valueNum = 35.0),
            ),
        )
        currentEnd = requireNotNull(store.find(childId, endId))
        assertEquals(shiftedEndMs, store.find(childId, startId)?.endedMs)
        assertEquals(35.0, currentEnd.valueNum ?: -1.0, 0.01)

        val badStart = runCatching {
            store.replace(
                childId,
                requireNotNull(store.find(childId, startId)).copy(
                    tsWallMs = shiftedEndMs + 1L,
                    startedMs = shiftedEndMs + 1L,
                ),
            )
        }.exceptionOrNull()
        assertTrue(badStart is IllegalArgumentException)
        assertEquals(shiftedStartMs, store.find(childId, startId)?.startedMs)

        val badEnd = runCatching {
            store.replace(
                childId,
                currentEnd.copy(tsWallMs = shiftedStartMs - 1L, endedMs = shiftedStartMs - 1L),
            )
        }.exceptionOrNull()
        assertTrue(badEnd is IllegalArgumentException)
        assertEquals(shiftedEndMs, store.find(childId, endId)?.endedMs)

        store.undoFinishSleep(childId, startId, endId, deletedAtMs = 100L)
        assertNull(store.find(childId, startId)?.endedMs)
        assertEquals(listOf(startId), store.recent(childId).map { it.id })
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingTrackerRows() {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE tracker_events(
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
                    trace_id TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO tracker_events(child_id, kind, ts_wall_ms, ts_mono_ms, source, note) " +
                    "VALUES('child-v1', 'NOTE', 1234, 5678, 'USER_ENTRY', 'preserved')"
            )
            db.version = 1
        }

        val store = TrackerStore.get(context)
        val migrated = store.recent("child-v1").single()
        assertEquals("preserved", migrated.note)
        assertEquals(1, store.softDelete("child-v1", migrated.id, deletedAtMs = 100L))
        assertTrue(store.recent("child-v1").isEmpty())
        assertEquals(1, store.restore("child-v1", migrated.id))
    }

    @Test
    fun eventEditorPrefillsUpdatesWithoutDuplicateAndUndoRestoresOriginal() {
        val childId = "child-editor"
        ChildProfileStore.get(context).apply {
            upsert(
                ChildProfile(
                    id = childId,
                    name = "Editor child",
                    dateOfBirthMs = System.currentTimeMillis() - 60L * TrackerTime.DAY_MS,
                )
            )
            setActiveId(childId)
        }
        val store = TrackerStore.get(context)
        val id = store.insert(
            TrackerEvent(
                childId = childId,
                kind = TrackerEvent.Kind.FEED_BOTTLE,
                subkind = TrackerEvent.FeedSubkind.BOTTLE_EBM,
                valueNum = 90.0,
                note = "before",
            )
        )
        val intent = android.content.Intent(context, TrackerEventEditActivity::class.java)
            .putExtra(TrackerEventEditActivity.EXTRA_CHILD_ID, childId)
            .putExtra(TrackerEventEditActivity.EXTRA_EVENT_ID, id)

        ActivityScenario.launch<TrackerEventEditActivity>(intent).use { scenario ->
            eventuallyEditEnabled(scenario, R.id.editSaveButton)
            scenario.onActivity { activity ->
                assertEquals("90", activity.findViewById<EditText>(R.id.editValue).text.toString())
                assertEquals("before", activity.findViewById<EditText>(R.id.editNote).text.toString())
                assertFalse(activity.findViewById<Spinner>(R.id.editKindSpinner).isEnabled)
                assertEquals(
                    activity.getString(R.string.tracker_feed_bottle_ebm),
                    activity.findViewById<Spinner>(R.id.editSubkindSpinner).selectedItem.toString(),
                )
                activity.findViewById<EditText>(R.id.editValue).setText("120")
                activity.findViewById<EditText>(R.id.editNote).setText("after")
                activity.findViewById<Button>(R.id.editSaveButton).performClick()
            }
            eventuallyValue(store, childId, id, expectedValue = 120.0, expectedNote = "after")
            assertEquals(1, store.recent(childId).size)

            eventuallyUndoVisible(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.trackerUndoButton).performClick()
            }
            eventuallyValue(store, childId, id, expectedValue = 90.0, expectedNote = "before")
            assertEquals(1, store.recent(childId).size)
        }
    }

    @Test
    fun eventLabelsCoverEveryKindAndHumanizeKnownStorageCodes() {
        TrackerEvent.Kind.values().forEach { kind ->
            assertTrue(TrackerEventLabels.kind(context, kind).isNotBlank())
        }
        val event = TrackerEvent(
            childId = "child-label",
            kind = TrackerEvent.Kind.FEED_BOTTLE,
            subkind = TrackerEvent.FeedSubkind.BOTTLE_EBM,
            valueNum = 125.0,
            note = "label note",
        )
        val label = TrackerEventLabels.event(context, event)
        assertFalse(label.contains("BOTTLE_EBM"))
        assertTrue(label.contains(context.getString(R.string.tracker_feed_bottle_ebm)))
        assertEquals("125 mL · label note", TrackerEventLabels.details(context, event))
        assertTrue(
            TrackerEventLabels.subtypeChoices(context, TrackerEvent.Kind.FEED_BOTTLE)
                .none { it.label.contains("BOTTLE_") },
        )
    }

    @Test
    fun insertAllRollsBackEveryRowWhenSecondInsertFails() {
        val store = TrackerStore.get(context)
        store.recent("child-rollback") // create/open the schema before installing the trigger
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TRIGGER fail_growth_length BEFORE INSERT ON tracker_events
                WHEN NEW.kind = 'GROWTH_LENGTH'
                BEGIN SELECT RAISE(ABORT, 'injected growth failure'); END
                """.trimIndent()
            )
        }

        val failed = runCatching {
            store.insertAll(
                listOf(
                    TrackerEvent(
                        childId = "child-rollback",
                        kind = TrackerEvent.Kind.GROWTH_WEIGHT,
                        valueNum = 5.2,
                    ),
                    TrackerEvent(
                        childId = "child-rollback",
                        kind = TrackerEvent.Kind.GROWTH_LENGTH,
                        valueNum = 58.0,
                    ),
                )
            )
        }.exceptionOrNull()

        assertTrue(failed is SQLiteException)
        assertTrue(store.recent("child-rollback").isEmpty())
    }

    @Test
    fun feedInsertFailureKeepsActivityAndEnteredValueForRetry() {
        val childId = "child-visible-failure"
        ChildProfileStore.get(context).apply {
            upsert(
                ChildProfile(
                    id = childId,
                    name = "Test child",
                    dateOfBirthMs = System.currentTimeMillis() - 30L * TrackerTime.DAY_MS,
                )
            )
            setActiveId(childId)
        }
        val store = TrackerStore.get(context)
        store.recent(childId)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TRIGGER fail_tracker_insert BEFORE INSERT ON tracker_events
                BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END
                """.trimIndent()
            )
        }

        val intent = android.content.Intent(context, FeedEntryActivity::class.java)
            .putExtra(BabyDashboardActivity.EXTRA_CHILD_ID, childId)
        ActivityScenario.launch<FeedEntryActivity>(intent).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            // #476: this test verifies write-failure state, not Espresso/window focus. Drive the
            // already-resumed activity directly on main so headless API 36 focus races cannot make
            // it fail before the injected SQLite trigger is exercised.
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.durationEdit).setText("30")
                activity.findViewById<Button>(R.id.saveButton).performClick()
            }

            eventuallyEnabled(scenario, R.id.saveButton)
            var entered = ""
            scenario.onActivity { activity ->
                entered = activity.findViewById<EditText>(R.id.durationEdit).text.toString()
            }
            assertEquals("30", entered)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            // API 21 can retain a schema-stale prepared query in TrackerStore's connection pool
            // after this test adds the failure trigger through a second SQLiteDatabase handle.
            // Verify the failed write through a fresh handle so SQLITE_MISMATCH from that platform
            // bug cannot hide the behavior under test: no feed row was persisted.
            context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
                db.rawQuery(
                    "SELECT COUNT(*) FROM tracker_events WHERE child_id = ?",
                    arrayOf(childId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
    }

    private fun eventuallyEnabled(scenario: ActivityScenario<FeedEntryActivity>, viewId: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var enabled = false
            scenario.onActivity { activity ->
                enabled = activity.findViewById<Button>(viewId).isEnabled
            }
            if (enabled) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("view $viewId did not become enabled")
    }

    private fun eventuallyEditEnabled(scenario: ActivityScenario<TrackerEventEditActivity>, viewId: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var enabled = false
            scenario.onActivity { activity -> enabled = activity.findViewById<Button>(viewId).isEnabled }
            if (enabled) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("view $viewId did not become enabled")
    }

    private fun eventuallyValue(
        store: TrackerStore,
        childId: String,
        id: Long,
        expectedValue: Double,
        expectedNote: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val event = store.find(childId, id)
            if (event?.valueNum == expectedValue && event.note == expectedNote) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("tracker event did not reach expected value/note")
    }

    private fun eventuallyUndoVisible(scenario: ActivityScenario<TrackerEventEditActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var visible = false
            scenario.onActivity { activity ->
                visible = activity.findViewById<Button>(R.id.trackerUndoButton) != null
            }
            if (visible) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("undo button did not appear")
    }

    companion object {
        private const val DATABASE_NAME = "tracker.db"
        private const val PROFILE_PREFS = "ChildProfiles"
    }
}
