package de.rochefort.childmonitor.tracker.guidance

/**
 * Safe sleep checklist surfaced to the parent before every sleep session.
 *
 * Phrases are drawn verbatim from CDC / AAP / Lullaby Trust - do not paraphrase.
 *
 * Sources:
 *  - CDC Sleep Safely: https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html
 *  - AAP HealthyChildren Safe Sleep: https://healthychildren.org/English/ages-stages/baby/sleep/Pages/A-Parents-Guide-to-Safe-Sleep.aspx
 *  - Lullaby Trust room temp: https://www.lullabytrust.org.uk/safer-sleep-advice/room-temperature/
 */
object SafeSleepChecklist {

    data class Item(
        val key: String,
        val prompt: String,
        val source: String,
        val severity: Severity = Severity.REQUIRED
    ) {
        enum class Severity { REQUIRED, RECOMMENDED, INFO }
    }

    val ITEMS: List<Item> = listOf(
        Item("back_position",
            "Place your baby on his or her back for all sleep times - naps and at night.",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html"),
        Item("firm_flat",
            "Use a firm, flat (not at an angle or inclined) sleep surface.",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html"),
        Item("room_share",
            "Keep your baby's sleep area in the same room where you sleep, ideally until your baby is at least 6 months old.",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html"),
        Item("no_soft_items",
            "Keep soft bedding out of your baby's sleep area: blankets, pillows, bumper pads, soft toys.",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html"),
        Item("no_head_cover",
            "Do not cover your baby's head or allow your baby to get too hot.",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html"),
        Item("smoke_free",
            "Keep the sleep environment smoke-free.",
            "https://www.nhs.uk/baby/caring-for-a-newborn/reduce-the-risk-of-sudden-infant-death-syndrome/"),
        Item("pacifier",
            "Offer your baby a pacifier at nap time and bedtime (once breastfeeding is established).",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html",
            Item.Severity.RECOMMENDED),
        Item("breastfeed_if_possible",
            "Feed your baby breastmilk when you can.",
            "https://www.cdc.gov/sudden-infant-death/sleep-safely/index.html",
            Item.Severity.RECOMMENDED),
        Item("temperature",
            "Keep the room temperature between 16 and 20 °C (60.8-68 °F).",
            "https://www.lullabytrust.org.uk/safer-sleep-advice/room-temperature/"),
        Item("stop_swaddle_rolling",
            "When your baby starts trying to roll over, stop swaddling them.",
            "https://healthychildren.org/English/ages-stages/baby/sleep/Pages/A-Parents-Guide-to-Safe-Sleep.aspx"),
        Item("no_seated_sleep",
            "Avoid sleep on a couch or armchair, or in a seating device like a swing or car safety seat.",
            "https://www.aap.org/en/patient-care/safe-sleep/")
    )

    /**
     * Keys of checklist items that must be true for this session. Unsafe
     * locations do NOT add a synthetic key here — use [isUnsafeLocation] for
     * that signal so the UI can surface a dedicated warning rather than a
     * phantom checkbox (CM-TRK-CR2-P1-6).
     */
    fun requiredForLocation(location: Location): Set<String> =
        ITEMS.filter { it.severity == Item.Severity.REQUIRED }.map { it.key }.toSet()

    fun isUnsafeLocation(location: Location): Boolean = when (location) {
        Location.PARENT_BED, Location.COUCH, Location.CAR_SEAT, Location.SWING,
        Location.BOUNCER, Location.INCLINED_SLEEPER -> true
        Location.CRIB, Location.BASSINET, Location.BEDSIDE_SLEEPER, Location.OTHER -> false
    }

    enum class Location {
        CRIB, BASSINET, BEDSIDE_SLEEPER, PARENT_BED, COUCH, CAR_SEAT, SWING,
        BOUNCER, INCLINED_SLEEPER, OTHER
    }

    fun roomTempAdvice(tempC: Double): String = when {
        tempC < 16.0 -> "Room is cold (< 16 °C). Add a layer and re-check in 10 minutes."
        tempC > 20.0 -> "Room is warm (> 20 °C). Remove a layer and ventilate to avoid overheating."
        else -> "Within the 16-20 °C safer-sleep band."
    }
}
