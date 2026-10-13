package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiaperThresholdsTest {

    @Test fun `day one expects at least one wet`() {
        assertEquals(1, DiaperThresholds.expectedWetByDayOfLife(1))
    }

    @Test fun `day six expects six`() {
        assertEquals(6, DiaperThresholds.expectedWetByDayOfLife(6))
    }

    @Test fun `day seven and beyond stay at six not eight`() {
        // [#236] regression: the wet-count floor is a sustained 6 from day 5, never 8.
        assertEquals(6, DiaperThresholds.expectedWetByDayOfLife(7))
        assertEquals(6, DiaperThresholds.expectedWetByDayOfLife(14))
        assertEquals(6, DiaperThresholds.expectedWetByDayOfLife(30))
    }

    @Test fun `six wet nappies on day 10 is not a dehydration alarm`() {
        val f = DiaperThresholds.evaluateWetCount(dayOfLife = 10, observedCount = 6)
        assertEquals(DiaperThresholds.Severity.OK, f.severity)
    }

    @Test fun `wet count ok when matching`() {
        val f = DiaperThresholds.evaluateWetCount(dayOfLife = 3, observedCount = 4)
        assertEquals(DiaperThresholds.Severity.OK, f.severity)
    }

    @Test fun `less than six wet after day 5 is urgent`() {
        val f = DiaperThresholds.evaluateWetCount(dayOfLife = 7, observedCount = 4)
        assertEquals(DiaperThresholds.Severity.URGENT, f.severity)
    }

    @Test fun `pale stool is urgent biliary atresia flag`() {
        val f = DiaperThresholds.evaluateColor(DiaperThresholds.StoolColor.WHITE_PALE_CHALKY, dayOfLife = 30)
        assertEquals(DiaperThresholds.Severity.URGENT, f.severity)
        assertTrue(f.message.contains("biliary"))
    }

    @Test fun `red stool is urgent`() {
        val f = DiaperThresholds.evaluateColor(DiaperThresholds.StoolColor.RED_BLOOD, dayOfLife = 30)
        assertEquals(DiaperThresholds.Severity.URGENT, f.severity)
    }

    @Test fun `meconium ok in first 3 days`() {
        val f = DiaperThresholds.evaluateColor(DiaperThresholds.StoolColor.BLACK_MECONIUM, dayOfLife = 2)
        assertEquals(DiaperThresholds.Severity.OK, f.severity)
    }

    @Test fun `diarrhoea threshold above 5 stools flags watch`() {
        val f = DiaperThresholds.evaluateDiarrhoea(stoolCountIn24h = 7, ageMonths = 6, fever = false)
        assertEquals(DiaperThresholds.Severity.WATCH, f.severity)
    }

    @Test fun `diarrhoea with fever in young infant is urgent`() {
        val f = DiaperThresholds.evaluateDiarrhoea(stoolCountIn24h = 7, ageMonths = 2, fever = true)
        assertEquals(DiaperThresholds.Severity.URGENT, f.severity)
    }
}
