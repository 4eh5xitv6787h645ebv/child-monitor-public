package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertTrue

class MilestoneChecklistTest {

    @Test fun `9 months has sitting item`() {
        val items = MilestoneChecklist.forAge(9)
        assertTrue(items.any { it.text.contains("Sits", ignoreCase = true) })
    }

    @Test fun `unmet red-flag items are reported`() {
        val report = MilestoneChecklist.evaluate(ageMonths = 12, unmetItemIds = setOf("12mo_word", "12mo_gesture"))
        assertTrue(report.red == 2)
    }

    @Test fun `age below first bucket yields empty list`() {
        assertTrue(MilestoneChecklist.forAge(1).isEmpty())
    }
}
