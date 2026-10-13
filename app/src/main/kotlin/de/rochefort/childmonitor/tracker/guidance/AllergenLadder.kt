package de.rochefort.childmonitor.tracker.guidance

/**
 * State machine for allergen introduction per LEAP + ASCIA + NIAID.
 *
 * Rules:
 *  - not_started → introduced after first logged exposure with no reaction in the first 2 hours.
 *  - introduced → established when:
 *      - At least 3 total exposures logged, AND
 *      - Maintenance dose met:
 *          peanut: >= 6 g protein / week across >= 3 sessions for >= 2 consecutive weeks.
 *          others: >= 1 serving / week for >= 4 consecutive weeks.
 *      - Zero mild / moderate reactions in the last 4 weeks.
 *  - Any logged reaction → avoid (locks allergen, surfaces ASCIA action plan).
 *  - established → "lapsing" (soft warning) when > 14 days since last exposure.
 *
 * Sources:
 *  - LEAP trial: https://pmc.ncbi.nlm.nih.gov/articles/PMC4416404/
 *  - NIAID addendum: https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/
 *  - ASCIA infant feeding: https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies
 *  - ASCIA Action Plan 2023: https://www.allergy.org.au/images/stories/anaphylaxis/2023/ASCIA_Action_Plan_Allergic_Reactions_Green_2023.pdf
 */
object AllergenLadder {

    enum class Status { NOT_STARTED, INTRODUCED, ESTABLISHED, LAPSING, AVOID }

    data class Exposure(
        val tsMs: Long,
        val proteinGrams: Double,
        val reaction: Reaction = Reaction.NONE
    )

    enum class Reaction { NONE, MILD_MODERATE, ANAPHYLAXIS }

    data class Evaluation(
        val status: Status,
        val rationale: String,
        val source: String
    )

    fun evaluate(allergen: String, exposures: List<Exposure>, nowMs: Long): Evaluation {
        if (exposures.isEmpty()) return Evaluation(
            Status.NOT_STARTED,
            "No exposures logged. Follow ASCIA guidance to introduce this allergen in the first year of life.",
            "https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies"
        )

        if (exposures.any { it.reaction == Reaction.ANAPHYLAXIS }) return Evaluation(
            Status.AVOID,
            "Anaphylaxis reaction recorded. Avoid further exposure until a specialist clears reintroduction. Use the ASCIA Action Plan in an emergency.",
            "https://www.allergy.org.au/images/stories/anaphylaxis/2023/ASCIA_Action_Plan_Allergic_Reactions_Green_2023.pdf"
        )

        val last4wkReactions = exposures.count {
            it.reaction == Reaction.MILD_MODERATE && nowMs - it.tsMs <= FOUR_WEEKS_MS
        }
        if (last4wkReactions > 0) return Evaluation(
            Status.AVOID,
            "Mild-to-moderate reaction in the last 4 weeks. Pause and discuss with your clinician before reintroducing.",
            "https://www.allergy.org.au/images/stories/anaphylaxis/2023/ASCIA_Action_Plan_Allergic_Reactions_Green_2023.pdf"
        )

        if (exposures.size < 3) {
            val lastExp = exposures.maxByOrNull { it.tsMs }
            val mins = ((nowMs - (lastExp?.tsMs ?: nowMs)) / 60_000L)
            return if (mins < 120) Evaluation(
                Status.INTRODUCED,
                "First exposure(s) recorded, still inside the initial watch window. Observe for 2 hours.",
                "https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/"
            ) else Evaluation(
                Status.INTRODUCED,
                "Allergen has been tolerated so far. Keep serving at least weekly to build tolerance.",
                "https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies"
            )
        }

        val last14dExposure = exposures.maxByOrNull { it.tsMs }
        val daysSinceLast = if (last14dExposure != null)
            (nowMs - last14dExposure.tsMs) / DAY_MS else Long.MAX_VALUE
        val established = when {
            allergen.equals("PEANUT", true) -> peanutMaintenance(exposures, nowMs)
            else -> genericMaintenance(exposures, nowMs)
        }

        return when {
            established && daysSinceLast > 14 -> Evaluation(
                Status.LAPSING,
                "Tolerance established but no recent exposure (> 14 days). Re-offer this week to maintain tolerance.",
                "https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies"
            )
            established -> Evaluation(
                Status.ESTABLISHED,
                "Maintenance frequency met with no recent reactions. Keep serving per ASCIA maintenance rules.",
                "https://www.allergy.org.au/patients/allergy-prevention/ascia-how-to-introduce-solid-foods-to-babies"
            )
            else -> Evaluation(
                Status.INTRODUCED,
                "Introduced but not yet established. Work up to weekly exposure.",
                "https://pmc.ncbi.nlm.nih.gov/articles/PMC5226648/"
            )
        }
    }

    private fun peanutMaintenance(exposures: List<Exposure>, nowMs: Long): Boolean {
        // >= 6 g protein / week across >= 3 sessions for >= 2 consecutive weeks.
        // Use a half-open range so an exposure exactly on the 7-day boundary
        // does not count in both weeks (CM-TRK-CR2-P1-4).
        val windows = listOf(
            exposures.filter { (nowMs - it.tsMs) in 0 until ONE_WEEK_MS },
            exposures.filter { (nowMs - it.tsMs) in ONE_WEEK_MS until (2 * ONE_WEEK_MS) }
        )
        return windows.all { w -> w.size >= 3 && w.sumOf { it.proteinGrams } >= 6.0 }
    }

    private fun genericMaintenance(exposures: List<Exposure>, nowMs: Long): Boolean {
        // >= 1 serving / week for >= 4 consecutive weeks. Half-open ranges
        // so the same exposure cannot count for two adjacent weeks.
        val ok = (0 until 4).all { weekIndex ->
            val windowStart = nowMs - (weekIndex + 1) * ONE_WEEK_MS
            val windowEnd = nowMs - weekIndex * ONE_WEEK_MS
            exposures.any { it.tsMs in windowStart until windowEnd }
        }
        return ok
    }

    private const val DAY_MS: Long = 24L * 3600L * 1000L
    private const val ONE_WEEK_MS: Long = 7L * DAY_MS
    private const val FOUR_WEEKS_MS: Long = 4L * ONE_WEEK_MS
}
