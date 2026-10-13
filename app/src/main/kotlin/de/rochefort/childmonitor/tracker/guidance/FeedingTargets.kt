package de.rochefort.childmonitor.tracker.guidance

/**
 * Feeding frequency + volume targets surfaced on the dashboard.
 *
 * Sources:
 *  - AAP frequency by age: https://www.healthychildren.org/English/ages-stages/baby/feeding-nutrition/Pages/how-often-and-how-much-should-your-baby-eat.aspx
 *  - AAP bottle schedule: https://www.healthychildren.org/English/ages-stages/baby/formula-feeding/Pages/amount-and-schedule-of-formula-feedings.aspx
 *  - CDC breast-milk storage: https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html
 *  - CDC formula prep & storage: https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html
 *  - NHS formula prep: https://www.nhs.uk/baby/breastfeeding-and-bottle-feeding/bottle-feeding/making-up-baby-formula/
 */
object FeedingTargets {

    data class Band(val ageMaxMonths: Int, val feedsPerDay: IntRange, val intervalHours: IntRange)

    val BREAST: List<Band> = listOf(
        Band(1, 8..12, 2..3),
        Band(3, 7..9, 2..3),
        Band(6, 5..7, 3..4),
        Band(12, 4..5, 3..5),
        Band(24, 3..4, 4..6)
    )

    val BOTTLE_VOLUME_ML: List<Pair<Int, IntRange>> = listOf(
        0 to 30..60,
        1 to 90..120,
        2 to 90..150,
        4 to 120..180,
        6 to 180..240,
        9 to 180..240,
        12 to 180..240
    )

    fun feedsPerDayTarget(ageMonths: Int): IntRange {
        return BREAST.firstOrNull { ageMonths <= it.ageMaxMonths }?.feedsPerDay ?: 3..4
    }

    fun intervalHoursTarget(ageMonths: Int): IntRange {
        return BREAST.firstOrNull { ageMonths <= it.ageMaxMonths }?.intervalHours ?: 4..6
    }

    fun bottleVolumeMlTarget(ageMonths: Int): IntRange {
        return BOTTLE_VOLUME_ML.lastOrNull { it.first <= ageMonths }?.second ?: 180..240
    }

    /**
     * Breast milk discard timing in ms from the moment of expression.
     * All values from the CDC breast-milk storage handling page.
     */
    enum class BreastStorage(val discardAfterMs: Long, val source: String) {
        ROOM_TEMP(4L * 3600_000, "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html"),
        FRIDGE(4L * 24 * 3600_000, "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html"),
        FREEZER(182L * 24 * 3600_000, "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html"),
        THAWED_FRIDGE(24L * 3600_000, "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html"),
        WARMED_ROOM_TEMP(2L * 3600_000, "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html"),
        STARTED_BOTTLE(2L * 3600_000, "https://www.cdc.gov/breastfeeding/breast-milk-preparation-and-storage/handling-breastmilk.html")
    }

    /** Formula discard timings from the CDC formula feeding page (stricter than breast milk). */
    enum class FormulaStorage(val discardAfterMs: Long, val source: String) {
        ROOM_TEMP_PREPARED(2L * 3600_000, "https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html"),
        FRIDGE_UNUSED(24L * 3600_000, "https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html"),
        STARTED_BOTTLE(1L * 3600_000, "https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/preparation-and-storage.html")
    }

    fun breastMilkUseBy(expressedAtMs: Long, storage: BreastStorage): Long =
        expressedAtMs + storage.discardAfterMs

    fun formulaUseBy(preparedAtMs: Long, storage: FormulaStorage): Long =
        preparedAtMs + storage.discardAfterMs
}
