package de.rochefort.childmonitor.events

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = EventStore(context)

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("events.db")
    }

    @Test
    fun soundEventUsesExplicitEpochInsteadOfDetectorElapsedTime() {
        val elapsedStart = 12_345L
        val epoch = 1_800_000_000_123L
        val event = SoundEventDetector.Event(
            type = "cry",
            startMs = elapsedStart,
            durationMs = 2_000L,
            peakDb = 80.0,
            confidence = 0.91,
        )

        val id = store.insertSoundEvent(event, occurredAtEpochMs = epoch, traceId = "epoch-test")
        val row = store.recent(1).getJSONObject(0)

        assertTrue(id > 0)
        assertEquals(id, row.getLong("id"))
        assertEquals(epoch, row.getLong("tsMs"))
        assertNotEquals(elapsedStart, row.getLong("tsMs"))
        assertEquals("cry", row.getString("type"))
        assertEquals(epoch, store.range(epoch, epoch).getJSONObject(0).getLong("tsMs"))
        assertEquals(0, store.deleteOlderThan(epoch)) // lower bound is intentionally exclusive
        assertEquals(1, store.deleteOlderThan(epoch + 1L))
    }
}
