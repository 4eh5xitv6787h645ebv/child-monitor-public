package de.rochefort.childmonitor.reliability

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZombieSessionDetectorTest {

    @Test fun `sweep returns empty when nothing tracked`() {
        val det = ZombieSessionDetector(staleMs = 100L)
        assertTrue(det.sweep().isEmpty())
    }

    @Test fun `fresh tracking not swept`() {
        val det = ZombieSessionDetector(staleMs = 60_000L)
        det.track("a")
        det.track("b")
        assertEquals(0, det.sweep().size)
        assertEquals(2, det.size())
    }

    @Test fun `stale entries are swept`() {
        var t = 1_000L
        val det = ZombieSessionDetector(staleMs = 0L, nowMs = { t })
        det.track("a")
        t += 5
        val zombies = det.sweep()
        assertTrue(zombies.contains("a"))
    }

    @Test fun `dropping removes tracking`() {
        val det = ZombieSessionDetector(staleMs = 0L)
        det.track("a")
        det.drop("a")
        assertFalse(det.sweep().contains("a"))
    }
}
