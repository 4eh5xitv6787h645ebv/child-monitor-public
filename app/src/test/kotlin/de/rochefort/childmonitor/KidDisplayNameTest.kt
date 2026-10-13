package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live-session label precedence for the Kids feature: parent kid name → advertised name → deviceId.
 */
class KidDisplayNameTest {

    @Test
    fun kidLabel_wins() {
        assertEquals("kid1", KidDisplayName.resolve("kid1", "Nursery Pixel", "893b97fd"))
    }

    @Test
    fun blankKidLabel_fallsThroughToAdvertised() {
        assertEquals("Nursery Pixel", KidDisplayName.resolve("  ", "Nursery Pixel", "893b97fd"))
        assertEquals("Nursery Pixel", KidDisplayName.resolve(null, "Nursery Pixel", "893b97fd"))
    }

    @Test
    fun advertisedBlank_fallsThroughToDeviceId() {
        assertEquals("893b97fd", KidDisplayName.resolve(null, "", "893b97fd"))
        assertEquals("893b97fd", KidDisplayName.resolve(null, null, "893b97fd"))
    }

    @Test
    fun allBlank_returnsNull() {
        assertNull(KidDisplayName.resolve(null, null, null))
        assertNull(KidDisplayName.resolve("  ", "", "  "))
    }
}
