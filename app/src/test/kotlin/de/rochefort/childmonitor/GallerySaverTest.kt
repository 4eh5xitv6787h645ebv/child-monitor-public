package de.rochefort.childmonitor

import de.rochefort.childmonitor.media.GallerySaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #288: snapshot → gallery. The only pure logic is the gallery filename — it must be deterministic,
 * timezone-stable (so two devices don't collide on ambiguity), filesystem-safe, and a .jpg.
 */
class GallerySaverTest {

    @Test
    fun fileNameIsStableUtcForAReferenceEpoch() {
        // 1_700_000_000_000 ms == 2023-11-14T22:13:20Z
        assertEquals("ChildMonitor-2023-11-14-22-13-20.jpg", GallerySaver.fileNameFor(1_700_000_000_000))
    }

    @Test
    fun fileNameHasJpgExtensionAndNoUnsafeChars() {
        val name = GallerySaver.fileNameFor(0L)
        assertTrue("ends with .jpg", name.endsWith(".jpg"))
        assertFalse("no path separators", name.contains('/') || name.contains('\\'))
        assertFalse("no spaces", name.contains(' '))
        assertFalse("no colons (illegal on many filesystems)", name.contains(':'))
    }

    @Test
    fun distinctSecondsProduceDistinctNames() {
        assertFalse(GallerySaver.fileNameFor(0L) == GallerySaver.fileNameFor(1_000L))
    }
}
