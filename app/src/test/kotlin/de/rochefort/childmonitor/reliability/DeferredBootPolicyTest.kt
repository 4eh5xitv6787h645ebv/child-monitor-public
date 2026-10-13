package de.rochefort.childmonitor.reliability

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DeferredBootPolicyTest {
    @Test fun `waits one minute after the fully booted signal`() {
        assertEquals(60_000L, DeferredBootPolicy.START_DELAY_MS)
        assertTrue(DeferredBootPolicy.shouldScheduleAfter("android.intent.action.BOOT_COMPLETED"))
    }

    @Test fun `locked boot never starts child mode`() {
        val action = "android.intent.action.LOCKED_BOOT_COMPLETED"
        assertTrue(DeferredBootPolicy.isBootAction(action))
        assertFalse(DeferredBootPolicy.shouldScheduleAfter(action))
    }

    @Test fun `quick boot gets the same stability delay and unrelated actions are ignored`() {
        assertTrue(DeferredBootPolicy.shouldScheduleAfter("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(DeferredBootPolicy.isBootAction("android.net.conn.CONNECTIVITY_CHANGE"))
    }
}
