package de.rochefort.childmonitor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #338: parent-side snapshot scrub logic — parse the timestamp list + map seek position → timestamp. */
class ReplayScrubPolicyTest {

    @Test fun parseTimestamps_valid() {
        val json = """{"timestamps":[100,200,300],"count":3}"""
        assertEquals(listOf(100L, 200L, 300L), ReplayScrubPolicy.parseTimestamps(json))
    }

    @Test fun parseTimestamps_sortsAscending() {
        val json = """{"timestamps":[300,100,200],"count":3}"""
        assertEquals(listOf(100L, 200L, 300L), ReplayScrubPolicy.parseTimestamps(json))
    }

    @Test fun parseTimestamps_emptyArray() {
        assertEquals(emptyList<Long>(), ReplayScrubPolicy.parseTimestamps("""{"timestamps":[],"count":0}"""))
    }

    @Test fun parseTimestamps_missingField() {
        assertEquals(emptyList<Long>(), ReplayScrubPolicy.parseTimestamps("""{"count":0}"""))
    }

    @Test fun parseTimestamps_malformed() {
        assertEquals(emptyList<Long>(), ReplayScrubPolicy.parseTimestamps("not json"))
        assertEquals(emptyList<Long>(), ReplayScrubPolicy.parseTimestamps(""))
    }

    @Test fun parseTimestamps_nonNumericElementFailsClosed() {
        // A hostile/garbled child body with a non-numeric element → fail closed to empty (no scrubber).
        assertEquals(emptyList<Long>(), ReplayScrubPolicy.parseTimestamps("""{"timestamps":[1,"x",3]}"""))
    }

    @Test fun clampIndex_bounds() {
        assertEquals(0, ReplayScrubPolicy.clampIndex(-5, 3))
        assertEquals(2, ReplayScrubPolicy.clampIndex(9, 3))
        assertEquals(1, ReplayScrubPolicy.clampIndex(1, 3))
        assertEquals(-1, ReplayScrubPolicy.clampIndex(0, 0))
    }

    @Test fun timestampAt_mapsPosition() {
        val ts = listOf(100L, 200L, 300L)
        assertEquals(100L, ReplayScrubPolicy.timestampAt(0, ts))
        assertEquals(300L, ReplayScrubPolicy.timestampAt(2, ts))
        assertEquals(300L, ReplayScrubPolicy.timestampAt(99, ts)) // clamps to last
        assertEquals(100L, ReplayScrubPolicy.timestampAt(-1, ts)) // clamps to first
        assertNull(ReplayScrubPolicy.timestampAt(0, emptyList()))
    }
}
