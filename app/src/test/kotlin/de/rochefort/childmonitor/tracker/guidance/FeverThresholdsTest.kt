package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertEquals

class FeverThresholdsTest {

    @Test fun `under 3 months any fever is HIGH`() {
        val a = FeverThresholds.evaluate(38.1, ageMonths = 1)
        assertEquals(FeverThresholds.Risk.HIGH, a.risk)
    }

    @Test fun `3-6 months 39C is intermediate`() {
        val a = FeverThresholds.evaluate(39.2, ageMonths = 4)
        assertEquals(FeverThresholds.Risk.INTERMEDIATE, a.risk)
    }

    @Test fun `over 6 months normal range`() {
        val a = FeverThresholds.evaluate(37.2, ageMonths = 12)
        assertEquals(FeverThresholds.Risk.NORMAL, a.risk)
    }

    @Test fun `40C+ is always emergency`() {
        val a = FeverThresholds.evaluate(40.1, ageMonths = 24)
        assertEquals(FeverThresholds.Risk.EMERGENCY, a.risk)
    }

    @Test fun `allowed routes gate infants under 4 weeks to axillary only`() {
        val routes = FeverThresholds.allowedRoutes(ageWeeks = 2)
        assertEquals(setOf(FeverThresholds.Route.AXILLARY_ELECTRONIC), routes)
    }

    // --- NICE NG143 traffic-light checklist (#150) ---

    @Test fun `empty checklist is green`() {
        assertEquals(
            FeverThresholds.TrafficLight.GREEN,
            FeverThresholds.trafficLight(FeverThresholds.Checklist())
        )
    }

    @Test fun `each red flag alone yields a red band`() {
        val redVariants = listOf(
            FeverThresholds.Checklist(paleMottledAshenBlue = true),
            FeverThresholds.Checklist(unresponsiveOrHardToRouse = true),
            FeverThresholds.Checklist(weakHighPitchedCry = true),
            FeverThresholds.Checklist(grunting = true),
            FeverThresholds.Checklist(apnoea = true),
            FeverThresholds.Checklist(respRateOver60 = true),
            FeverThresholds.Checklist(reducedSkinTurgor = true),
            FeverThresholds.Checklist(nonBlanchingRash = true),
            FeverThresholds.Checklist(neuroSigns = true),
        )
        redVariants.forEach { checklist ->
            assertEquals(
                FeverThresholds.TrafficLight.RED,
                FeverThresholds.trafficLight(checklist),
                "expected RED for $checklist"
            )
        }
    }

    @Test fun `tachycardia alone is amber`() {
        assertEquals(
            FeverThresholds.TrafficLight.AMBER,
            FeverThresholds.trafficLight(FeverThresholds.Checklist(tachycardiaForAge = true))
        )
    }

    @Test fun `red outranks amber when both present`() {
        val both = FeverThresholds.Checklist(tachycardiaForAge = true, grunting = true)
        assertEquals(FeverThresholds.TrafficLight.RED, FeverThresholds.trafficLight(both))
    }

    @Test fun `life-threatening red flags are EMERGENCY even without fever`() {
        val lifeThreatening = listOf(
            FeverThresholds.Checklist(nonBlanchingRash = true),
            FeverThresholds.Checklist(apnoea = true),
            FeverThresholds.Checklist(neuroSigns = true),
            FeverThresholds.Checklist(unresponsiveOrHardToRouse = true),
            FeverThresholds.Checklist(paleMottledAshenBlue = true),
        )
        lifeThreatening.forEach { checklist ->
            val a = FeverThresholds.evaluate(36.8, ageMonths = 12, checklist = checklist)
            assertEquals(FeverThresholds.Risk.EMERGENCY, a.risk, "expected EMERGENCY for $checklist")
        }
    }

    @Test fun `other red flags are HIGH even without fever`() {
        val urgentButNotEmergency = listOf(
            FeverThresholds.Checklist(weakHighPitchedCry = true),
            FeverThresholds.Checklist(grunting = true),
            FeverThresholds.Checklist(respRateOver60 = true),
            FeverThresholds.Checklist(reducedSkinTurgor = true),
        )
        urgentButNotEmergency.forEach { checklist ->
            val a = FeverThresholds.evaluate(36.8, ageMonths = 12, checklist = checklist)
            assertEquals(FeverThresholds.Risk.HIGH, a.risk, "expected HIGH for $checklist")
        }
    }

    @Test fun `amber checklist lifts a low fever to INTERMEDIATE`() {
        val a = FeverThresholds.evaluate(
            38.5, ageMonths = 12,
            checklist = FeverThresholds.Checklist(tachycardiaForAge = true)
        )
        assertEquals(FeverThresholds.Risk.INTERMEDIATE, a.risk)
    }

    @Test fun `temperature emergency outranks a red checklist`() {
        val a = FeverThresholds.evaluate(
            40.2, ageMonths = 12,
            checklist = FeverThresholds.Checklist(grunting = true)
        )
        assertEquals(FeverThresholds.Risk.EMERGENCY, a.risk)
    }

    @Test fun `green checklist falls back to temperature banding`() {
        val a = FeverThresholds.evaluate(38.1, ageMonths = 1, checklist = FeverThresholds.Checklist())
        assertEquals(FeverThresholds.Risk.HIGH, a.risk)
    }

    @Test fun `red rationale names the triggered flags`() {
        val a = FeverThresholds.evaluate(
            38.5, ageMonths = 12,
            checklist = FeverThresholds.Checklist(grunting = true, nonBlanchingRash = true)
        )
        kotlin.test.assertTrue(a.rationale.contains("grunting"), "rationale was: ${a.rationale}")
        kotlin.test.assertTrue(a.rationale.contains("non-blanching"), "rationale was: ${a.rationale}")
    }

    @Test fun `tachycardia thresholds follow NICE age bands`() {
        assertEquals(160, FeverThresholds.tachycardiaThresholdBpm(ageMonths = 0))
        assertEquals(160, FeverThresholds.tachycardiaThresholdBpm(ageMonths = 11))
        assertEquals(150, FeverThresholds.tachycardiaThresholdBpm(ageMonths = 12))
        assertEquals(150, FeverThresholds.tachycardiaThresholdBpm(ageMonths = 23))
        assertEquals(140, FeverThresholds.tachycardiaThresholdBpm(ageMonths = 24))
        assertEquals(140, FeverThresholds.tachycardiaThresholdBpm(ageMonths = 48))
    }
}
