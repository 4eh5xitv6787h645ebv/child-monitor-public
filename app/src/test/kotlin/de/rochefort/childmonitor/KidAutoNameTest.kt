package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Auto-naming applied when a child is paired by QR: name immediately so the kid becomes a
 * permanent link (survives the recency cap, reconnects keep the name). Covers blank/null
 * advertised names, advertised-name reuse, cross-device collisions, sequential kidN, no-clobber
 * on re-scan of an already-named device, and MAX_LEN truncation.
 */
class KidAutoNameTest {

    private fun child(
        deviceId: String,
        name: String = "child",
        userName: String? = null
    ): PairedChild = PairedChild(
        deviceId = deviceId,
        name = name,
        lastKnownIp = "10.0.0.1",
        lastKnownPort = 10000,
        lastSeenMs = 0L,
        userName = userName,
        createdAtMs = 0L
    )

    @Test
    fun blankOrNullAdvertisedName_yieldsKid1() {
        assertEquals("kid1", KidAutoName.assign(null, "dev-a", emptyList()))
        assertEquals("kid1", KidAutoName.assign("", "dev-a", emptyList()))
        assertEquals("kid1", KidAutoName.assign("   ", "dev-a", emptyList()))
    }

    @Test
    fun advertisedName_usedWhenFree() {
        assertEquals("Living Room", KidAutoName.assign("Living Room", "dev-a", emptyList()))
        // Surrounding whitespace is trimmed.
        assertEquals("Living Room", KidAutoName.assign("  Living Room  ", "dev-a", emptyList()))
    }

    @Test
    fun advertisedName_collidesWithDifferentDevice_fallsBackToKidN() {
        val existing = listOf(child("dev-other", userName = "Tablet"))
        // "Tablet" is taken by a DIFFERENT device, so the new device can't reuse it.
        assertEquals("kid1", KidAutoName.assign("Tablet", "dev-new", existing))
    }

    @Test
    fun sequentialKidN_skipsTakenNames() {
        val existing = listOf(child("dev-1", userName = "kid1"))
        // kid1 is taken → smallest unused is kid2.
        assertEquals("kid2", KidAutoName.assign(null, "dev-new", existing))

        val existing2 = listOf(
            child("dev-1", userName = "kid1"),
            child("dev-2", userName = "kid2")
        )
        assertEquals("kid3", KidAutoName.assign("", "dev-new", existing2))
    }

    @Test
    fun rescanOfAlreadyNamedDevice_keepsExistingName_noClobber() {
        val existing = listOf(child("dev-a", userName = "My Kid"))
        // Re-scanning dev-a must return its existing user name, ignoring the advertised name.
        assertEquals("My Kid", KidAutoName.assign("Some New Advertised Name", "dev-a", existing))
        // Even with a blank advertised name, the manual name is preserved.
        assertEquals("My Kid", KidAutoName.assign(null, "dev-a", existing))
    }

    @Test
    fun rescanOfDeviceWithBlankUserName_doesNotCountAsNamed() {
        // An existing entry for this device with a blank/absent userName is not a manual rename,
        // so we proceed to derive a fresh name from the advertised name.
        val existing = listOf(child("dev-a", userName = null))
        assertEquals("Bedroom", KidAutoName.assign("Bedroom", "dev-a", existing))
    }

    @Test
    fun advertisedName_truncatedToMaxLen() {
        val longName = "x".repeat(KidAutoName.MAX_LEN + 25)
        val result = KidAutoName.assign(longName, "dev-a", emptyList())
        assertEquals(KidAutoName.MAX_LEN, result.length)
        assertEquals("x".repeat(KidAutoName.MAX_LEN), result)
    }
}
