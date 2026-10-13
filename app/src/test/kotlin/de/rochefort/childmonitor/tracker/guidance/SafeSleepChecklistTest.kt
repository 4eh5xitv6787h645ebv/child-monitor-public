package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertTrue

class SafeSleepChecklistTest {

    @Test fun `parent bed flagged unsafe`() {
        assertTrue(SafeSleepChecklist.isUnsafeLocation(SafeSleepChecklist.Location.PARENT_BED))
    }

    @Test fun `crib is safe`() {
        assertTrue(!SafeSleepChecklist.isUnsafeLocation(SafeSleepChecklist.Location.CRIB))
    }

    @Test fun `required set only contains real checklist keys`() {
        val keys = SafeSleepChecklist.ITEMS.map { it.key }.toSet()
        for (loc in SafeSleepChecklist.Location.values()) {
            val required = SafeSleepChecklist.requiredForLocation(loc)
            assertTrue(required.all { it in keys },
                "Required set for $loc references keys that aren't in ITEMS")
        }
    }

    @Test fun `room temp below 16 is flagged`() {
        val advice = SafeSleepChecklist.roomTempAdvice(14.0)
        assertTrue(advice.contains("cold", ignoreCase = true))
    }

    @Test fun `room temp above 20 is flagged`() {
        val advice = SafeSleepChecklist.roomTempAdvice(22.0)
        assertTrue(advice.contains("warm", ignoreCase = true))
    }
}
