package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertTrue

class GrowthPercentilesTest {

    @Test fun `median weight is near 50th percentile`() {
        // 3-month male median weight is 6.38 kg in the WHO LMS table.
        val p = GrowthPercentiles.percentile(GrowthPercentiles.Reference.WFA_MALE, 6.38, ageMonths = 3)
        assertTrue(p in 45.0..55.0, "expected around 50th pct, got $p")
    }

    @Test fun `low weight is low percentile`() {
        val p = GrowthPercentiles.percentile(GrowthPercentiles.Reference.WFA_MALE, 4.5, ageMonths = 3)
        assertTrue(p < 10.0)
    }

    @Test fun `high weight is high percentile`() {
        val p = GrowthPercentiles.percentile(GrowthPercentiles.Reference.WFA_MALE, 8.2, ageMonths = 3)
        assertTrue(p > 85.0)
    }

    @Test fun `length median is around 50th pct`() {
        val p = GrowthPercentiles.percentile(GrowthPercentiles.Reference.LFA_FEMALE, 59.8, ageMonths = 3)
        assertTrue(p in 45.0..55.0, "expected around 50th pct, got $p")
    }
}
