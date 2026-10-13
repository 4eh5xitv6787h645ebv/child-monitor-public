package de.rochefort.childmonitor.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** #310: round-trip + untrusted-input validation for the backup prefs serializer. */
class BackupSerializerTest {

    private val sample = mapOf(
        "ListenPreferences" to mapOf(
            "muteAlerts" to true,
            "themeIndex" to 3,
            "lastSeenMs" to 1_700_000_000_000L,
            "gain" to 1.25f,
            "pairedChildren_v1" to """[{"deviceId":"abc","userName":"kid1"}]""",
            "ipHistory" to linkedSetOf("192.168.0.5", "192.168.0.9"),
        ),
        "ParentSession" to mapOf(
            "token_v1::abc" to "deadbeef",
            "https_port_v1::abc" to 11443,
        ),
    )

    @Test fun roundTripsAllValueTypes() {
        val json = BackupSerializer.serialize(sample, createdAtMs = 1_750_000_000_000L)
        val back = BackupSerializer.parse(json)
        assertEquals(BackupSerializer.VERSION, back.version)
        assertEquals(1_750_000_000_000L, back.createdAtMs)
        assertEquals(true, back.prefs["ListenPreferences"]!!["muteAlerts"])
        assertEquals(3, back.prefs["ListenPreferences"]!!["themeIndex"])
        assertEquals(1_700_000_000_000L, back.prefs["ListenPreferences"]!!["lastSeenMs"])
        assertEquals(1.25f, back.prefs["ListenPreferences"]!!["gain"] as Float, 0.0001f)
        assertEquals("""[{"deviceId":"abc","userName":"kid1"}]""", back.prefs["ListenPreferences"]!!["pairedChildren_v1"])
        assertEquals(linkedSetOf("192.168.0.5", "192.168.0.9"), back.prefs["ListenPreferences"]!!["ipHistory"])
        assertEquals("deadbeef", back.prefs["ParentSession"]!!["token_v1::abc"])
        assertEquals(11443, back.prefs["ParentSession"]!!["https_port_v1::abc"])
    }

    @Test fun typesSurviveExactly() {
        // Int vs Long must not collapse — the apply layer dispatches putInt/putLong by runtime type.
        // Build the map explicitly: a `mapOf("i" to 5, "l" to 5L)` literal would have Kotlin unify the
        // value type to Long and coerce the `5` literal, defeating the point.
        val inner = LinkedHashMap<String, Any?>()
        inner["i"] = 5
        inner["l"] = 5L
        val json = BackupSerializer.serialize(mapOf("F" to inner), 0)
        val back = BackupSerializer.parse(json)
        assertTrue("i was ${back.prefs["F"]!!["i"]?.javaClass}", back.prefs["F"]!!["i"] is Int)
        assertTrue("l was ${back.prefs["F"]!!["l"]?.javaClass}", back.prefs["F"]!!["l"] is Long)
    }

    @Test fun nullValuesAreSkipped() {
        val json = BackupSerializer.serialize(mapOf("F" to mapOf("a" to null, "b" to "x")), 0)
        val back = BackupSerializer.parse(json)
        assertEquals(setOf("b"), back.prefs["F"]!!.keys)
    }

    @Test fun rejectsNonJson() {
        assertThrows(BackupSerializer.Invalid::class.java) { BackupSerializer.parse("not json {{{") }
    }

    @Test fun rejectsWrongFormatMarker() {
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"something-else","version":1,"prefs":{}}""")
        }
    }

    @Test fun rejectsUnsupportedVersion() {
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":99,"prefs":{}}""")
        }
    }

    @Test fun rejectsUnknownTypeTag() {
        val hostile = """{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"zz","v":1}}}}"""
        assertThrows(BackupSerializer.Invalid::class.java) { BackupSerializer.parse(hostile) }
    }

    @Test fun rejectsMissingPrefs() {
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1}""")
        }
    }

    @Test fun rejectsOversizedString() {
        val big = "x".repeat(BackupSerializer.MAX_STRING_LEN + 1)
        val hostile = """{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"s","v":"$big"}}}}"""
        assertThrows(BackupSerializer.Invalid::class.java) { BackupSerializer.parse(hostile) }
    }

    @Test fun emptyPrefsRoundTrips() {
        val back = BackupSerializer.parse(BackupSerializer.serialize(emptyMap(), 0))
        assertTrue(back.prefs.isEmpty())
    }

    @Test fun nonFiniteFloatIsStoredAsFinite() {
        // org.json rejects NaN/Infinity; serialize must not crash (coerceIn(NaN) can yield NaN).
        val inner = LinkedHashMap<String, Any?>()
        inner["nan"] = Float.NaN
        inner["inf"] = Float.POSITIVE_INFINITY
        val back = BackupSerializer.parse(BackupSerializer.serialize(mapOf("F" to inner), 0))
        assertEquals(0f, back.prefs["F"]!!["nan"] as Float, 0f)
        assertEquals(0f, back.prefs["F"]!!["inf"] as Float, 0f)
    }

    @Test fun emptySetAndEmptyStringRoundTrip() {
        val inner = LinkedHashMap<String, Any?>()
        inner["empties"] = linkedSetOf("")
        inner["emptyset"] = linkedSetOf<String>()
        inner["emptystr"] = ""
        val back = BackupSerializer.parse(BackupSerializer.serialize(mapOf("F" to inner), 0))
        assertEquals(linkedSetOf(""), back.prefs["F"]!!["empties"])
        assertEquals(linkedSetOf<String>(), back.prefs["F"]!!["emptyset"])
        assertEquals("", back.prefs["F"]!!["emptystr"])
    }

    // Type-confusion: a known tag carrying the wrong JSON kind must be rejected, not coerced.
    @Test fun rejectsStringTagWithNumberValue() {
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"s","v":123}}}}""")
        }
    }

    @Test fun rejectsNumberTagWithStringValue() {
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"i","v":"abc"}}}}""")
        }
    }

    @Test fun rejectsFractionalOrOutOfRangeIntTag() {
        // A fractional or out-of-int-range value under t:"i" must be rejected, not silently truncated/wrapped.
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"i","v":5.5}}}}""")
        }
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"i","v":5000000000}}}}""")
        }
    }

    @Test fun rejectsNonStringSetItem() {
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"ss","v":[1,2]}}}}""")
        }
    }

    @Test fun rejectsTooManyFiles() {
        val files = (0..BackupSerializer.MAX_FILES).joinToString(",") { """"f$it":{}""" }
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{$files}}""")
        }
    }

    @Test fun rejectsOversizedSet() {
        val items = (0..BackupSerializer.MAX_SET_SIZE).joinToString(",") { "\"$it\"" }
        assertThrows(BackupSerializer.Invalid::class.java) {
            BackupSerializer.parse("""{"format":"child-monitor-backup","version":1,"prefs":{"F":{"k":{"t":"ss","v":[$items]}}}}""")
        }
    }
}
