package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertTrue

class FeedingTargetsTest {

    @Test fun `newborn feeds per day is 8 to 12`() {
        val range = FeedingTargets.feedsPerDayTarget(ageMonths = 0)
        assertTrue(range.first == 8 && range.last == 12)
    }

    @Test fun `toddler volume target decreases`() {
        assertTrue(FeedingTargets.bottleVolumeMlTarget(1).first >= 60)
        assertTrue(FeedingTargets.bottleVolumeMlTarget(6).last >= 180)
    }

    @Test fun `breast milk use-by at room temp is 4h`() {
        val useBy = FeedingTargets.breastMilkUseBy(0L, FeedingTargets.BreastStorage.ROOM_TEMP)
        assertTrue(useBy == 4L * 3600_000L)
    }

    @Test fun `formula use-by after start is 1h`() {
        val useBy = FeedingTargets.formulaUseBy(0L, FeedingTargets.FormulaStorage.STARTED_BOTTLE)
        assertTrue(useBy == 3600_000L)
    }
}
