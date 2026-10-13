package de.rochefort.childmonitor.tracker.guidance

/**
 * Diaper / elimination evaluation.
 *
 * Sources:
 *  - NHS enough milk (wet-count-by-day-of-life): https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/breastfeeding-problems/enough-milk/
 *  - NICE CG84 gastroenteritis: https://www.nice.org.uk/guidance/cg84/chapter/Recommendations
 *  - NICE NG224 UTI in under 16s: https://www.nice.org.uk/guidance/ng224
 *  - AAP Many Colors of Poop: https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx
 *  - CLDF Yellow Alert stool chart (pale/white => biliary atresia): https://childliverdisease.org/wp-content/uploads/2019/04/CLDF-Yellow-Alert-Stool-Chart.pdf
 */
object DiaperThresholds {

    enum class StoolColor {
        YELLOW_MUSTARD, TAN_BROWN, GREEN,
        BLACK_MECONIUM, BLACK_MELENA,
        RED_BLOOD, WHITE_PALE_CHALKY, OTHER
    }

    enum class StoolConsistency { SEEDY, PASTY, FORMED, HARD_PELLETS, WATERY }

    enum class Severity { OK, WATCH, URGENT }

    data class Finding(
        val severity: Severity,
        val code: String,
        val message: String,
        val source: String
    )

    /**
     * Expected minimum wet count by day of life. From day 5 onward this is a sustained floor
     * of 6 per NHS ("at least 6 heavy, wet nappies every 24 hours" from day 5); it does NOT
     * keep climbing. [#236] the previous value of 8 from day 7 falsely flagged healthy
     * 1-2 week-old babies (with the textbook 6-7 wet nappies) as a dehydration red flag.
     */
    fun expectedWetByDayOfLife(dayOfLife: Int): Int = when {
        dayOfLife <= 0 -> 0
        dayOfLife == 1 -> 1
        dayOfLife == 2 -> 2
        dayOfLife == 3 -> 3
        dayOfLife == 4 -> 4
        else -> 6
    }

    fun evaluateWetCount(dayOfLife: Int, observedCount: Int): Finding {
        val expected = expectedWetByDayOfLife(dayOfLife)
        return when {
            observedCount >= expected -> Finding(
                severity = Severity.OK,
                code = "wet_ok",
                message = "Wet count meets expected minimum for day $dayOfLife (>= $expected).",
                source = "https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/breastfeeding-problems/enough-milk/"
            )
            dayOfLife <= 5 -> Finding(
                severity = Severity.WATCH,
                code = "wet_below_expected_early",
                message = "Wet nappies ($observedCount) below day $dayOfLife minimum ($expected). Offer more feeds and watch for dehydration.",
                source = "https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/breastfeeding-problems/enough-milk/"
            )
            else -> Finding(
                severity = Severity.URGENT,
                code = "wet_dehydration_risk",
                message = "Fewer than 6 wet nappies in 24h after day 5 is a dehydration red flag. Contact your clinician.",
                source = "https://www.nice.org.uk/guidance/cg84/chapter/Recommendations"
            )
        }
    }

    fun evaluateColor(color: StoolColor, dayOfLife: Int): Finding = when (color) {
        StoolColor.YELLOW_MUSTARD -> ok("Normal mustard stool for a breastfed baby.")
        StoolColor.TAN_BROWN -> ok("Normal tan stool for a formula-fed baby.")
        StoolColor.GREEN -> ok("Green stools are a common variation after day 5.")
        StoolColor.BLACK_MECONIUM -> {
            if (dayOfLife <= 3) ok("Meconium is expected in the first few days.")
            else urgent("black_after_meconium",
                "Black stool after day 3 is unexpected and could be GI bleeding. Contact your clinician.",
                "https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx")
        }
        StoolColor.BLACK_MELENA -> urgent("melena",
            "Black tarry stool (after day 5) can indicate GI bleeding - urgent assessment.",
            "https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx")
        StoolColor.RED_BLOOD -> urgent("blood_in_stool",
            "Red / bloody stool is not normal - contact your clinician today.",
            "https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx")
        StoolColor.WHITE_PALE_CHALKY -> urgent("pale_stool_biliary_atresia",
            "Pale or white stools can signal biliary atresia - a time-critical liver condition. See the CLDF Yellow Alert card and contact your clinician today.",
            "https://childliverdisease.org/wp-content/uploads/2019/04/CLDF-Yellow-Alert-Stool-Chart.pdf")
        StoolColor.OTHER -> Finding(
            severity = Severity.WATCH,
            code = "unusual_color",
            message = "Unusual colour - photograph and show your clinician.",
            source = "https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx"
        )
    }

    fun evaluateConsistency(consistency: StoolConsistency, ageWeeks: Int): Finding = when (consistency) {
        StoolConsistency.SEEDY, StoolConsistency.PASTY, StoolConsistency.FORMED -> ok("Normal consistency.")
        StoolConsistency.WATERY -> watch("watery",
            "Watery stools can be early diarrhoea; watch for wet count drops and fever.",
            "https://www.nice.org.uk/guidance/cg84/chapter/Recommendations")
        StoolConsistency.HARD_PELLETS -> {
            if (ageWeeks < 6) ok("Small firm pellets can be normal in the first 6 weeks as the gut settles.")
            else watch("constipation",
                "Hard pellet stools after 6 weeks may be constipation. Hydrate well and seek advice if recurrent.",
                "https://www.healthychildren.org/English/ages-stages/baby/diapers-clothing/Pages/Infant-Constipation.aspx")
        }
    }

    fun evaluateDiarrhoea(stoolCountIn24h: Int, ageMonths: Int, fever: Boolean): Finding {
        if (stoolCountIn24h <= 5) return ok("Within the usual range; keep hydrating.")
        if (fever && ageMonths < 3) return urgent("diarrhoea_with_fever_young",
            "Diarrhoea with fever in an infant under 3 months needs urgent review.",
            "https://www.nice.org.uk/guidance/cg84/chapter/Recommendations")
        return watch("diarrhoea",
            "More than 5 loose stools in 24h raises dehydration risk (NICE CG84). Use oral rehydration and watch closely.",
            "https://www.nice.org.uk/guidance/cg84/chapter/Recommendations")
    }

    private fun ok(message: String) = Finding(
        Severity.OK, "ok", message,
        "https://www.healthychildren.org/English/ages-stages/baby/Pages/The-Many-Colors-of-Poop.aspx"
    )

    private fun watch(code: String, message: String, source: String) =
        Finding(Severity.WATCH, code, message, source)

    private fun urgent(code: String, message: String, source: String) =
        Finding(Severity.URGENT, code, message, source)
}
