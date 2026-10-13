package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedRunCoreTest {

    private val noop: (String, String) -> Unit = { _, _ -> }

    @Test fun `admits up to the cap then refuses`() {
        val core = BoundedRunCore(2, noop)
        assertTrue(core.submit("a", "t"))
        assertTrue(core.submit("b", "t"))
        assertFalse(core.submit("c", "t"), "over the cap of 2 must be refused")
        assertEquals(0, core.availablePermits())
    }

    @Test fun `completed frees a slot for reuse`() {
        val core = BoundedRunCore(1, noop)
        assertTrue(core.submit("a", "t"))
        assertFalse(core.submit("b", "t"))
        core.completed("a")
        assertTrue(core.submit("b", "t"), "a freed slot must be reusable")
    }

    @Test fun `a failed spawn reclaims the slot instead of leaking it`() {
        val core = BoundedRunCore<String>(1) { _, _ -> throw RuntimeException("OS refused thread") }
        assertFailsWith<RuntimeException> { core.submit("a", "t") }
        // The permit must be back and the item must not linger as running.
        assertEquals(1, core.availablePermits())
        assertTrue(core.runningSnapshot().isEmpty())
    }

    @Test fun `completed is idempotent and does not over-release`() {
        val core = BoundedRunCore(1, noop)
        core.submit("a", "t")            // 0 permits left
        core.completed("unknown")        // not admitted → no release
        assertEquals(0, core.availablePermits())
        core.completed("a")              // releases the real one → 1
        core.completed("a")              // second call must be a no-op
        assertEquals(1, core.availablePermits())
    }

    @Test fun `runningSnapshot reflects admitted items and is a copy`() {
        val core = BoundedRunCore(4, noop)
        core.submit("a", "t")
        core.submit("b", "t")
        val snap = core.runningSnapshot()
        assertEquals(setOf("a", "b"), snap.toSet())
        core.completed("a")
        assertEquals(setOf("a", "b"), snap.toSet(), "snapshot must be an independent copy")
        assertEquals(setOf("b"), core.runningSnapshot().toSet())
    }

    @Test fun `spawn receives the item and a uniquely-suffixed thread name`() {
        val names = mutableListOf<String>()
        val core = BoundedRunCore<String>(4) { _, name -> names.add(name) }
        core.submit("a", "cm-http")
        core.submit("b", "cm-http")
        assertEquals(listOf("cm-http-1", "cm-http-2"), names)
    }
}
