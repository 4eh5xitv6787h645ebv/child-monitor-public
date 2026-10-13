package de.rochefort.childmonitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the "Kids" roster: a parent names a child device (e.g. "kid1") so it
 * becomes a PERMANENT link that survives IP changes and reconnect churn. Verifies the two
 * invariants in [PairedChildRoster]: reconnect upserts never clobber a user name, and a named
 * kid is never evicted by the recency cap. Also covers [PairedChild] display-name resolution,
 * permanence, and JSON round-trip / back-compat.
 */
class PairedChildRosterTest {

    private fun child(
        id: String,
        ip: String = "192.168.0.10",
        name: String = id,
        port: Int = 10000,
        lastSeen: Long = 0L,
        userName: String? = null,
        createdAt: Long = 0L
    ) = PairedChild(id, name, ip, port, lastSeen, userName, createdAt)

    // ---- PairedChild model ----

    @Test
    fun displayName_prefersUserNameThenAdvertisedThenId() {
        assertEquals("kid1", child("abcd", name = "Nursery Pixel", userName = "kid1").displayName)
        assertEquals("Nursery Pixel", child("abcd", name = "Nursery Pixel").displayName)
        assertEquals("abcd", child("abcd", name = "").displayName)
        // A blank user name falls through to the advertised name.
        assertEquals("Nursery Pixel", child("abcd", name = "Nursery Pixel", userName = "   ").displayName)
    }

    @Test
    fun isPermanent_onlyWhenNamed() {
        assertFalse(child("abcd").isPermanent)
        assertTrue(child("abcd", userName = "kid1").isPermanent)
        assertFalse(child("abcd", userName = "  ").isPermanent)
    }

    @Test
    fun json_roundTripsAllFields() {
        val c = child("abcd", ip = "192.168.0.42", name = "Pixel", port = 10001,
            lastSeen = 123L, userName = "kid1", createdAt = 99L)
        val back = PairedChild.fromJson(c.toJson())!!
        assertEquals(c, back)
    }

    @Test
    fun json_omitsUserNameWhenUnset() {
        val json = child("abcd").toJson()
        assertFalse(json.has("userName"))
        val back = PairedChild.fromJson(json)!!
        assertNull(back.userName)
        assertEquals(0L, back.createdAtMs)
    }

    @Test
    fun json_backCompat_legacyRecordWithoutNewKeys() {
        // A record written before the Kids feature: no userName/createdAtMs keys.
        val legacy = JSONObject()
            .put("deviceId", "893b97fd")
            .put("name", "Nursery")
            .put("ip", "192.168.0.114")
            .put("port", 10000)
            .put("lastSeenMs", 555L)
        val back = PairedChild.fromJson(legacy)!!
        assertEquals("893b97fd", back.deviceId)
        assertNull(back.userName)
        assertEquals(0L, back.createdAtMs)
        assertFalse(back.isPermanent)
    }

    // ---- upserted: merge-preserve ----

    @Test
    fun upsert_newChild_stampsCreatedAtAndMovesToFront() {
        val before = listOf(child("aaaa"))
        val after = PairedChildRoster.upserted(before, child("bbbb", lastSeen = 7L), nowMs = 1000L)
        assertEquals("bbbb", after.first().deviceId)
        assertEquals(1000L, after.first().createdAtMs)
        assertEquals(2, after.size)
    }

    @Test
    fun upsert_reconnect_doesNotClobberUserName() {
        // User named "aaaa" as kid1 (permanent). A reconnect upsert arrives with the CHILD-advertised
        // name + a new IP and NO userName — it must keep the user's name, update the IP.
        val existing = listOf(child("aaaa", ip = "192.168.0.10", name = "Old", userName = "kid1", createdAt = 50L))
        val incoming = child("aaaa", ip = "192.168.0.250", name = "Pixel 3 (advertised)", lastSeen = 900L)
        val after = PairedChildRoster.upserted(existing, incoming, nowMs = 1000L)
        val merged = after.single()
        assertEquals("kid1", merged.userName)            // preserved
        assertTrue(merged.isPermanent)                    // still permanent
        assertEquals(50L, merged.createdAtMs)             // original kept
        assertEquals("192.168.0.250", merged.lastKnownIp) // updated
        assertEquals("Pixel 3 (advertised)", merged.name) // child-advertised name updated
        assertEquals("kid1", merged.displayName)          // still shows the user's name
    }

    @Test
    fun upsert_incomingUserName_overridesWhenProvided() {
        val existing = listOf(child("aaaa", userName = "kid1"))
        val incoming = child("aaaa", userName = "kidA")
        val after = PairedChildRoster.upserted(existing, incoming, nowMs = 1L)
        assertEquals("kidA", after.single().userName)
    }

    // ---- withUserName / removed ----

    @Test
    fun withUserName_setsNameAndMakesPermanent() {
        val before = listOf(child("aaaa"))
        val after = PairedChildRoster.withUserName(before, "aaaa", "  kid1  ")
        val c = after.single()
        assertEquals("kid1", c.userName) // trimmed
        assertTrue(c.isPermanent)
    }

    @Test
    fun withUserName_clearing_demotesToEvictable() {
        val before = listOf(child("aaaa", userName = "kid1"))
        val after = PairedChildRoster.withUserName(before, "aaaa", null)
        val c = after.single()
        assertNull(c.userName)
        assertFalse(c.isPermanent)
    }

    @Test
    fun withUserName_absentDevice_isNoOp() {
        val before = listOf(child("aaaa"))
        assertEquals(before, PairedChildRoster.withUserName(before, "zzzz", "kid1"))
    }

    @Test
    fun removed_dropsEntry() {
        val before = listOf(child("aaaa"), child("bbbb"))
        val after = PairedChildRoster.removed(before, "aaaa")
        assertEquals(listOf("bbbb"), after.map { it.deviceId })
    }

    // ---- capped: never evict permanent ----

    @Test
    fun cap_dropsOldestTransientBeyondMax() {
        val list = (1..12).map { child("d%02d".format(it)) } // 12 transient, most-recent-first
        val capped = PairedChildRoster.capped(list, max = 10)
        assertEquals(10, capped.size)
        // The two oldest (tail) are dropped; order preserved.
        assertEquals((1..10).map { "d%02d".format(it) }, capped.map { it.deviceId })
    }

    @Test
    fun cap_neverEvictsPermanent_evenAtTail() {
        // A permanent kid sitting at the very tail must survive even when transient churn exceeds the cap.
        val transient = (1..11).map { child("t%02d".format(it)) }
        val list = transient + child("kid", userName = "kid1") // permanent, oldest position
        val capped = PairedChildRoster.capped(list, max = 10)
        assertTrue(capped.any { it.deviceId == "kid" })
        // Exactly one transient is dropped to make room for keeping all permanents within the cap.
        assertEquals(10, capped.size)
        assertEquals(1, capped.count { it.isPermanent })
    }

    @Test
    fun cap_preservesOrderWithPermanentInterleaved() {
        // Permanent entry in the MIDDLE among transients, total over the cap.
        val list = listOf(
            child("t1"), child("t2"), child("kid", userName = "kid1"),
            child("t3"), child("t4"), child("t5")
        )
        val capped = PairedChildRoster.capped(list, max = 4)
        // 4 kept: the permanent always survives; the 3 most-recent transients fill the rest; order kept.
        assertEquals(listOf("t1", "t2", "kid", "t3"), capped.map { it.deviceId })
    }

    @Test
    fun cap_keepsAllPermanentsEvenBeyondMax() {
        val perm = (1..12).map { child("k%02d".format(it), userName = "kid$it") }
        val capped = PairedChildRoster.capped(perm, max = 10)
        assertEquals(12, capped.size) // permanence wins over the cap
    }

    @Test
    fun upsert_keepsPermanentWhenManyDevicesChurn() {
        // End-to-end: name one kid, then 11 other devices connect (upsert). The named kid stays.
        var roster = PairedChildRoster.withUserName(listOf(child("kid")), "kid", "kid1")
        for (i in 1..11) {
            roster = PairedChildRoster.upserted(roster, child("o%02d".format(i)), nowMs = i.toLong())
        }
        assertTrue(roster.any { it.deviceId == "kid" && it.userName == "kid1" })
    }
}
