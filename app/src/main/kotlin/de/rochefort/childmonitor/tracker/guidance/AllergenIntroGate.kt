package de.rochefort.childmonitor.tracker.guidance

import de.rochefort.childmonitor.tracker.ChildProfile.AllergyRiskTier

/**
 * NIAID high-risk pre-introduction testing gate (#163).
 *
 * The NIAID Addendum Guidelines classify infants with severe eczema and/or egg
 * allergy as HIGH risk for peanut allergy, and recommend a specialist consult
 * plus peanut sIgE or skin-prick testing BEFORE the first home peanut exposure.
 * This gate blocks logging that first exposure for HIGH-tier infants on the
 * pre-introduction allergens until a parent confirms their clinician has cleared
 * it (the override, persisted per child+allergen).
 *
 * The block applies to the first exposure only — once an allergen is past
 * [AllergenLadder.Status.NOT_STARTED] the ladder's own reaction-driven rules
 * take over. Pure logic so it is unit-testable on the JVM.
 *
 * Source: NIAID Addendum (Togias et al. 2017) https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/
 */
object AllergenIntroGate {

    /**
     * Allergens for which a HIGH-tier infant needs pre-introduction testing.
     * Peanut is the NIAID Addendum's direct subject; egg is included because a
     * documented egg allergy is itself a HIGH-risk marker and egg is a common
     * early-introduction allergen, per the issue's clinical scope.
     */
    val PRE_INTRO_TEST_ALLERGENS = setOf("PEANUT", "EGG")

    const val MESSAGE =
        "Talk to your clinician first. NIAID recommends sIgE or skin-prick testing before the " +
            "first peanut exposure for babies with severe eczema or egg allergy. Do not give the " +
            "first exposure at home until a specialist has cleared it."

    const val SOURCE = "https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/"

    data class Decision(
        val blocked: Boolean,
        val message: String? = null,
        val source: String? = null
    )

    fun evaluate(
        riskTier: AllergyRiskTier,
        allergen: String,
        status: AllergenLadder.Status,
        preIntroTestOk: Boolean
    ): Decision {
        val blocked = riskTier == AllergyRiskTier.HIGH &&
            allergen.uppercase() in PRE_INTRO_TEST_ALLERGENS &&
            status == AllergenLadder.Status.NOT_STARTED &&
            !preIntroTestOk
        return if (blocked) Decision(true, MESSAGE, SOURCE) else Decision(false)
    }
}
