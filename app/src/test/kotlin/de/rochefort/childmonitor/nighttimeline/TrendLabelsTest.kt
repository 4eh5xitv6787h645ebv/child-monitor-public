package de.rochefort.childmonitor.nighttimeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #388: stride/anchor math that thins MultiNightTrendsView's centered x-axis date labels. */
class TrendLabelsTest {

    @Test fun stride1_whenLabelFitsInSlot() {
        // 75px slot, 60px label — fits with room → label every night.
        assertEquals(1, TrendLabels.labelStride(slotPx = 75f, maxLabelPx = 60f))
    }

    @Test fun stride2_whenLabelExceedsOneSlot() {
        // 40px slot, 70px label → need 2 slots of spacing.
        assertEquals(2, TrendLabels.labelStride(slotPx = 40f, maxLabelPx = 70f))
    }

    @Test fun stride3_whenLabelExceedsTwoSlots() {
        // 30px slot, 70px label → ceil(70/30) = 3.
        assertEquals(3, TrendLabels.labelStride(slotPx = 30f, maxLabelPx = 70f))
    }

    @Test fun gap_pushesToNextStride() {
        // Without a gap 60/30 = 2; an 8px gap makes 68/30 = ceil 3.
        assertEquals(2, TrendLabels.labelStride(slotPx = 30f, maxLabelPx = 60f, gapPx = 0f))
        assertEquals(3, TrendLabels.labelStride(slotPx = 30f, maxLabelPx = 60f, gapPx = 8f))
    }

    @Test fun exactMultiple_doesNotRoundUp() {
        // 60px label in 30px slots is exactly 2 — must not become 3.
        assertEquals(2, TrendLabels.labelStride(slotPx = 30f, maxLabelPx = 60f))
    }

    @Test fun nonPositiveInputs_fallBackToEveryNight() {
        assertEquals(1, TrendLabels.labelStride(slotPx = 0f, maxLabelPx = 60f))
        assertEquals(1, TrendLabels.labelStride(slotPx = 50f, maxLabelPx = 0f))
        assertEquals(1, TrendLabels.labelStride(slotPx = -10f, maxLabelPx = 60f))
    }

    @Test fun negativeGap_treatedAsZero() {
        assertEquals(2, TrendLabels.labelStride(slotPx = 30f, maxLabelPx = 60f, gapPx = -100f))
    }

    @Test fun shouldLabel_everyNight_whenStride1() {
        val count = 5
        for (i in 0 until count) assertTrue(TrendLabels.shouldLabel(i, count, stride = 1))
    }

    @Test fun shouldLabel_anchorsToMostRecentNight() {
        // 14 nights, stride 3, anchored to last index → label 13, 10, 7, 4, 1.
        val count = 14
        val stride = 3
        val labeled = (0 until count).filter { TrendLabels.shouldLabel(it, count, stride) }
        assertEquals(listOf(1, 4, 7, 10, 13), labeled)
        assertTrue("most recent night always labeled", TrendLabels.shouldLabel(count - 1, count, stride))
        assertFalse(TrendLabels.shouldLabel(12, count, stride))
    }

    @Test fun shouldLabel_singleNight_alwaysLabeled_regardlessOfStride() {
        // The headline edge case from #388: one night must always be labeled, any stride.
        for (stride in 1..7) assertTrue(TrendLabels.shouldLabel(0, count = 1, stride = stride))
    }

    @Test fun shouldLabel_stride2_labelsEveryOther_fromEnd() {
        val count = 6 // indices 0..5; from end every 2 → 5,3,1
        val labeled = (0 until count).filter { TrendLabels.shouldLabel(it, count, stride = 2) }
        assertEquals(listOf(1, 3, 5), labeled)
    }
}
