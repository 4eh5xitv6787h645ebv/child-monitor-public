package de.rochefort.childmonitor.tracker.guidance

import de.rochefort.childmonitor.tracker.ChildProfile.AllergyRiskTier
import de.rochefort.childmonitor.tracker.guidance.AllergenLadder.Status
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllergenIntroGateTest {

    private fun gate(
        tier: AllergyRiskTier,
        allergen: String,
        status: Status = Status.NOT_STARTED,
        testOk: Boolean = false
    ) = AllergenIntroGate.evaluate(tier, allergen, status, testOk)

    @Test fun `high-risk first peanut exposure is blocked pending a clinician test`() {
        val d = gate(AllergyRiskTier.HIGH, "PEANUT")
        assertTrue(d.blocked)
        assertTrue(d.message!!.isNotBlank())
        assertTrue(d.source!!.startsWith("https://"))
    }

    @Test fun `high-risk first egg exposure is also blocked`() {
        assertTrue(gate(AllergyRiskTier.HIGH, "EGG").blocked)
    }

    @Test fun `the clinician-cleared override lifts the block`() {
        assertFalse(gate(AllergyRiskTier.HIGH, "PEANUT", testOk = true).blocked)
    }

    @Test fun `block only applies to the first (not-started) exposure`() {
        listOf(Status.INTRODUCED, Status.ESTABLISHED, Status.LAPSING, Status.AVOID).forEach { s ->
            assertFalse(gate(AllergyRiskTier.HIGH, "PEANUT", status = s).blocked, "should allow once $s")
        }
    }

    @Test fun `non-high-risk tiers are not gated`() {
        assertFalse(gate(AllergyRiskTier.MODERATE, "PEANUT").blocked)
        assertFalse(gate(AllergyRiskTier.LOW, "PEANUT").blocked)
    }

    @Test fun `allergens outside the NIAID pre-intro set are not gated`() {
        listOf("MILK", "WHEAT", "SOY", "SESAME", "TREE_NUT", "FISH", "SHELLFISH").forEach { a ->
            assertFalse(gate(AllergyRiskTier.HIGH, a).blocked, "should allow $a")
        }
    }

    @Test fun `allergen match is case-insensitive`() {
        assertTrue(gate(AllergyRiskTier.HIGH, "peanut").blocked)
    }

    @Test fun `an allowed decision carries no message`() {
        val d = gate(AllergyRiskTier.LOW, "PEANUT")
        assertFalse(d.blocked)
        assertEquals(null, d.message)
    }
}
