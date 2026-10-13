package de.rochefort.childmonitor.tracker.guidance

/**
 * Fever evaluation aligned with NICE NG143 "Fever in under 5s" and AAP HealthyChildren.
 *
 * Thresholds:
 *  - < 3 mo: any temperature >= 38.0 °C is a red-flag ("URGENT").
 *  - 3-6 mo: >= 39.0 °C is an intermediate risk flag.
 *  - > 6 mo: temperature alone is not diagnostic - fall back to the traffic-light checklist.
 *  - Any age: persistent >= 40.0 °C prompts a clinician call.
 *
 * Sources:
 *  - NICE NG143 §1.2.11-13: https://www.nice.org.uk/guidance/ng143/chapter/Recommendations
 *  - AAP When to Call: https://www.healthychildren.org/English/health-issues/conditions/fever/Pages/When-to-Call-the-Pediatrician.aspx
 */
object FeverThresholds {

    enum class Risk { NORMAL, LOW, INTERMEDIATE, HIGH, EMERGENCY }

    data class Assessment(
        val risk: Risk,
        val headline: String,
        val rationale: String,
        val primarySource: String
    )

    /**
     * Normalise an arbitrary measurement route to the axillary-equivalent
     * temperature that the [evaluate] thresholds expect. Sources:
     *  - NHS https://www.nhs.uk/conditions/fever-in-children/
     *  - AAP https://www.healthychildren.org/English/health-issues/conditions/fever/Pages/How-to-Take-a-Childs-Temperature.aspx
     *
     * Rectal ≈ axillary + 0.5 (rectal reads ~0.5 °C HIGHER than axillary).
     * Oral and tympanic are roughly comparable to axillary on NICE charts;
     * chemical-dot strips are pre-calibrated as axillary equivalents.
     *
     * Returns the temperature mapped into the axillary frame so callers can
     * pass it directly to the age-banded threshold tables.
     */
    fun toAxillaryEquivalent(tempCelsius: Double, route: Route): Double = when (route) {
        Route.AXILLARY_ELECTRONIC, Route.CHEMICAL_DOT, Route.TYMPANIC -> tempCelsius
        Route.RECTAL -> tempCelsius - 0.5
        Route.ORAL -> tempCelsius        // NICE treats oral as ~axillary in toddlers
        Route.TEMPORAL -> tempCelsius    // most temporal thermometers ship pre-calibrated to axillary
    }

    /**
     * Route-aware evaluation (#192-1). Without a route, a 38.0 °C rectal
     * reading (≈ 37.5 axillary, NOT a fever in &lt;3 mo) used to read the
     * same as 38.0 axillary (which IS a red-flag in &lt;3 mo). Callers with
     * an unknown route can call the route-less overload below — it defaults
     * to axillary which matches the legacy behaviour.
     */
    fun evaluate(tempCelsius: Double, ageMonths: Int, route: Route): Assessment {
        val axillary = toAxillaryEquivalent(tempCelsius, route)
        return evaluate(axillary, ageMonths)
    }

    fun evaluate(tempCelsius: Double, ageMonths: Int): Assessment {
        if (tempCelsius >= 40.0) {
            return Assessment(
                risk = Risk.EMERGENCY,
                headline = "Call emergency services",
                rationale = "Temperature at or above 40 °C is a medical emergency at any age.",
                primarySource = "https://www.healthychildren.org/English/health-issues/conditions/fever/Pages/When-to-Call-the-Pediatrician.aspx"
            )
        }
        return when {
            ageMonths < 3 -> evalUnder3mo(tempCelsius)
            ageMonths < 6 -> eval3to6mo(tempCelsius)
            else -> evalOver6mo(tempCelsius)
        }
    }

    private fun evalUnder3mo(t: Double): Assessment {
        if (t >= 38.0) return Assessment(
            risk = Risk.HIGH,
            headline = "Contact a clinician now",
            rationale = "Infants under 3 months with any temperature >= 38 °C are treated as high risk. Call 111, a GP, or go to A&E.",
            primarySource = "https://www.nice.org.uk/guidance/ng143/chapter/Recommendations"
        )
        return Assessment(
            risk = Risk.NORMAL,
            headline = "No fever",
            rationale = "Under 3 months, normal axillary is below 38 °C. Keep monitoring.",
            primarySource = "https://www.nice.org.uk/guidance/ng143/chapter/Recommendations"
        )
    }

    private fun eval3to6mo(t: Double): Assessment {
        return when {
            t >= 39.0 -> Assessment(
                risk = Risk.INTERMEDIATE,
                headline = "Call your GP",
                rationale = "3-6 months with >= 39 °C is an intermediate-risk red flag. A clinician review is recommended.",
                primarySource = "https://www.nice.org.uk/guidance/ng143/chapter/Recommendations"
            )
            t >= 38.0 -> Assessment(
                risk = Risk.LOW,
                headline = "Fever - monitor",
                rationale = "Fever present; cross-reference the traffic-light checklist and hydrate.",
                primarySource = "https://www.nhs.uk/conditions/fever-in-children/"
            )
            else -> Assessment(
                risk = Risk.NORMAL,
                headline = "No fever",
                rationale = "Within normal range for age.",
                primarySource = "https://www.nhs.uk/conditions/fever-in-children/"
            )
        }
    }

    private fun evalOver6mo(t: Double): Assessment {
        return when {
            t >= 39.5 -> Assessment(
                risk = Risk.INTERMEDIATE,
                headline = "Check traffic-light signs",
                rationale = "Over 6 months the temperature alone is not diagnostic; check the NICE traffic-light checklist for amber/red features.",
                primarySource = "https://www.nice.org.uk/guidance/ng143/chapter/Recommendations"
            )
            t >= 38.0 -> Assessment(
                risk = Risk.LOW,
                headline = "Fever - hydrate and monitor",
                rationale = "Support fluids; monitor behaviour; consider an antipyretic if in distress.",
                primarySource = "https://www.nhs.uk/conditions/fever-in-children/"
            )
            else -> Assessment(
                risk = Risk.NORMAL,
                headline = "No fever",
                rationale = "Normal range.",
                primarySource = "https://www.nhs.uk/conditions/fever-in-children/"
            )
        }
    }

    /**
     * Map a NICE measurement-route rule set to the ages where the route is allowed.
     * Used by the UI to pre-select a valid thermometer type for the baby's age.
     */
    fun allowedRoutes(ageWeeks: Int): Set<Route> = when {
        ageWeeks < 4 -> setOf(Route.AXILLARY_ELECTRONIC)
        ageWeeks <= 5 * 52 -> setOf(Route.AXILLARY_ELECTRONIC, Route.TYMPANIC, Route.CHEMICAL_DOT)
        else -> Route.values().toSet()
    }

    enum class Route { AXILLARY_ELECTRONIC, TYMPANIC, CHEMICAL_DOT, RECTAL, ORAL, TEMPORAL }

    // --- NICE NG143 traffic-light symptom checklist (#150) ---

    enum class TrafficLight { GREEN, AMBER, RED }

    /**
     * Observable symptom checklist per NICE NG143 Table 1 ("Traffic light system
     * for identifying risk of serious illness"). Each flag maps to a table row:
     * all are red-column features except [tachycardiaForAge], which NICE places
     * in the amber circulation row. [apnoea] is drawn from the companion sepsis
     * red-flag list (NG51) and treated as red here.
     *
     * NG143 does not age-band its red-column rows (the table covers all under-5s;
     * only the amber tachypnoea/tachycardia thresholds vary by age), so every red
     * flag here applies at any age. The amber tachycardia row is the one age-banded
     * row surfaced, via [tachycardiaThresholdBpm].
     *
     * [neuroSigns] bundles the meningism red flags: stiff neck, photophobia,
     * bulging fontanelle, or seizure.
     */
    data class Checklist(
        val paleMottledAshenBlue: Boolean = false,
        val unresponsiveOrHardToRouse: Boolean = false,
        val weakHighPitchedCry: Boolean = false,
        val grunting: Boolean = false,
        val apnoea: Boolean = false,
        val respRateOver60: Boolean = false,
        val tachycardiaForAge: Boolean = false,
        val reducedSkinTurgor: Boolean = false,
        val nonBlanchingRash: Boolean = false,
        val neuroSigns: Boolean = false,
    )

    /**
     * Amber tachycardia cut-off per NICE NG143 Table 1 (beats/min):
     * >160 under 12 months, >150 at 12-24 months, >140 from 2 years.
     * Surfaced so the UI can label the checkbox with the age-correct number.
     */
    fun tachycardiaThresholdBpm(ageMonths: Int): Int = when {
        ageMonths < 12 -> 160
        ageMonths < 24 -> 150
        else -> 140
    }

    /** Highest traffic-light band triggered by the checklist alone. */
    fun trafficLight(checklist: Checklist): TrafficLight = when {
        redFlagLabels(checklist).isNotEmpty() -> TrafficLight.RED
        amberFlagLabels(checklist).isNotEmpty() -> TrafficLight.AMBER
        else -> TrafficLight.GREEN
    }

    /**
     * Checklist-aware evaluation (#150). NICE NG143 states that over 6 months
     * temperature alone is not diagnostic; the symptom checklist can only raise
     * the temperature-based band, never lower it. Red features are HIGH at any
     * temperature, and the immediately life-threatening subset (see
     * [emergencyFlagLabels]) is EMERGENCY - a non-blanching rash or seizure
     * outranks even a 40 °C reading, never the reverse.
     */
    fun evaluate(tempCelsius: Double, ageMonths: Int, checklist: Checklist): Assessment {
        val byTemperature = evaluate(tempCelsius, ageMonths)
        val byChecklist = checklistAssessment(checklist) ?: return byTemperature
        return if (byChecklist.risk >= byTemperature.risk) byChecklist else byTemperature
    }

    private fun checklistAssessment(checklist: Checklist): Assessment? = when (trafficLight(checklist)) {
        TrafficLight.RED -> {
            val emergency = emergencyFlagLabels(checklist)
            val urgent = urgentFlagLabels(checklist)
            if (emergency.isNotEmpty()) Assessment(
                risk = Risk.EMERGENCY,
                headline = "Call emergency services now",
                rationale = "Immediately life-threatening features: ${emergency.joinToString(", ")}. " +
                    (if (urgent.isNotEmpty()) "Also present: ${urgent.joinToString(", ")}. " else "") +
                    "NICE treats these as an emergency at any temperature - call 999/112 now.",
                primarySource = SOURCE_NICE_NG143
            ) else Assessment(
                risk = Risk.HIGH,
                headline = "Red flags - get urgent medical help now",
                rationale = "Red traffic-light features: ${redFlagLabels(checklist).joinToString(", ")}. " +
                    "NICE treats any red feature as high risk regardless of temperature - " +
                    "call emergency services or go to A&E.",
                primarySource = SOURCE_NICE_NG143
            )
        }
        TrafficLight.AMBER -> Assessment(
            risk = Risk.INTERMEDIATE,
            headline = "Amber signs - contact a clinician today",
            rationale = "Amber traffic-light features: ${amberFlagLabels(checklist).joinToString(", ")}. " +
                "Arrange a same-day clinical review (GP or 111).",
            primarySource = SOURCE_NICE_NG143
        )
        TrafficLight.GREEN -> null
    }

    /**
     * Red flags that NICE escalates past "urgent review" to an immediate
     * emergency response (NG143 §1.5 referral + NG51 sepsis red flags):
     * circulatory compromise, altered consciousness, apnoea, meningism,
     * and the meningococcal non-blanching rash.
     */
    private fun emergencyFlagLabels(c: Checklist): List<String> = buildList {
        if (c.paleMottledAshenBlue) add("pale/mottled/ashen/blue skin")
        if (c.unresponsiveOrHardToRouse) add("unresponsive or hard to rouse")
        if (c.apnoea) add("apnoea")
        if (c.nonBlanchingRash) add("non-blanching rash")
        if (c.neuroSigns) add("stiff neck/photophobia/bulging fontanelle/seizure")
    }

    /** Red-column features that warrant urgent review but not an automatic 999 call. */
    private fun urgentFlagLabels(c: Checklist): List<String> = buildList {
        if (c.weakHighPitchedCry) add("weak or high-pitched cry")
        if (c.grunting) add("grunting")
        if (c.respRateOver60) add("breathing rate above 60/min")
        if (c.reducedSkinTurgor) add("reduced skin turgor")
    }

    private fun redFlagLabels(c: Checklist): List<String> =
        emergencyFlagLabels(c) + urgentFlagLabels(c)

    private fun amberFlagLabels(c: Checklist): List<String> = buildList {
        if (c.tachycardiaForAge) add("tachycardia for age")
    }

    private const val SOURCE_NICE_NG143 = "https://www.nice.org.uk/guidance/ng143/chapter/Recommendations"
}
