package de.rochefort.childmonitor.tracker.guidance

/**
 * CDC "Learn the Signs. Act Early." 2022 revision - 75% attainment age buckets.
 *
 * Source: https://www.cdc.gov/milestones/index.html (individual pages under
 * /act-early/milestones/<age>-months.html)
 *
 * The full CDC checklist has ~6-10 items per age across four domains. This file
 * ships the "most likely to concern" trigger per age - the item whose absence at
 * that age is the strongest case for a clinician conversation. A full checklist
 * can be layered on via a JSON asset later.
 */
object MilestoneChecklist {

    enum class Domain { SOCIAL_EMOTIONAL, LANGUAGE_COMMUNICATION, COGNITIVE, MOVEMENT_PHYSICAL }

    data class Item(
        val id: String,
        val ageMonths: Int,
        val domain: Domain,
        val text: String,
        val redFlagWhenAbsent: Boolean,
        val source: String
    )

    val AGE_BUCKETS: IntArray = intArrayOf(2, 4, 6, 9, 12, 15, 18, 24, 30, 36, 48, 60)

    val ITEMS: List<Item> = listOf(
        Item("2mo_smile", 2, Domain.SOCIAL_EMOTIONAL,
            "Smiles at people.",
            true,
            "https://www.cdc.gov/act-early/milestones/2-months.html"),
        Item("2mo_sound", 2, Domain.LANGUAGE_COMMUNICATION,
            "Reacts to loud sounds.",
            true,
            "https://www.cdc.gov/act-early/milestones/2-months.html"),
        Item("4mo_hands_to_mouth", 4, Domain.MOVEMENT_PHYSICAL,
            "Brings hands to mouth.",
            true,
            "https://www.cdc.gov/act-early/milestones/4-months.html"),
        Item("4mo_coo", 4, Domain.LANGUAGE_COMMUNICATION,
            "Coos and makes sounds back when you talk.",
            true,
            "https://www.cdc.gov/act-early/milestones/4-months.html"),
        Item("6mo_reaches", 6, Domain.MOVEMENT_PHYSICAL,
            "Reaches to grab a toy they want.",
            true,
            "https://www.cdc.gov/act-early/milestones/6-months.html"),
        Item("6mo_affection", 6, Domain.SOCIAL_EMOTIONAL,
            "Shows affection for caregivers.",
            true,
            "https://www.cdc.gov/act-early/milestones/6-months.html"),
        Item("9mo_babble", 9, Domain.LANGUAGE_COMMUNICATION,
            "Babbles (mamama, bababa).",
            true,
            "https://www.cdc.gov/act-early/milestones/9-months.html"),
        Item("9mo_name", 9, Domain.LANGUAGE_COMMUNICATION,
            "Reacts to their own name.",
            true,
            "https://www.cdc.gov/act-early/milestones/9-months.html"),
        Item("9mo_sit", 9, Domain.MOVEMENT_PHYSICAL,
            "Sits without support.",
            true,
            "https://www.cdc.gov/act-early/milestones/9-months.html"),
        Item("12mo_gesture", 12, Domain.LANGUAGE_COMMUNICATION,
            "Uses simple gestures (wave, point).",
            true,
            "https://www.cdc.gov/act-early/milestones/12-months.html"),
        Item("12mo_word", 12, Domain.LANGUAGE_COMMUNICATION,
            "Says a single word besides \"mama\" or \"dada\".",
            true,
            "https://www.cdc.gov/act-early/milestones/12-months.html"),
        Item("15mo_three_words", 15, Domain.LANGUAGE_COMMUNICATION,
            "Says 3+ words besides \"mama\" and \"dada\".",
            true,
            "https://www.cdc.gov/act-early/milestones/15-months.html"),
        Item("18mo_walk", 18, Domain.MOVEMENT_PHYSICAL,
            "Walks without help.",
            true,
            "https://www.cdc.gov/act-early/milestones/18-months.html"),
        Item("18mo_point", 18, Domain.SOCIAL_EMOTIONAL,
            "Points to show something interesting.",
            true,
            "https://www.cdc.gov/act-early/milestones/18-months.html"),
        Item("24mo_two_words", 24, Domain.LANGUAGE_COMMUNICATION,
            "Says two-word phrases like \"more milk\".",
            true,
            "https://www.cdc.gov/act-early/milestones/2-year.html"),
        Item("24mo_common_use", 24, Domain.COGNITIVE,
            "Knows what familiar objects are for (phone, brush, spoon).",
            true,
            "https://www.cdc.gov/act-early/milestones/2-year.html"),
        Item("30mo_short_sentence", 30, Domain.LANGUAGE_COMMUNICATION,
            "Says 2-3 word sentences.",
            true,
            "https://www.cdc.gov/act-early/milestones/30-months.html"),
        Item("36mo_pretend", 36, Domain.COGNITIVE,
            "Plays pretend with toys or with you.",
            true,
            "https://www.cdc.gov/act-early/milestones/3-year.html"),
        Item("48mo_story", 48, Domain.LANGUAGE_COMMUNICATION,
            "Tells you a simple story about something they did.",
            true,
            "https://www.cdc.gov/act-early/milestones/4-year.html"),
        Item("60mo_full_name", 60, Domain.LANGUAGE_COMMUNICATION,
            "Says first and last name on request.",
            true,
            "https://www.cdc.gov/act-early/milestones/5-year.html")
    )

    fun forAge(ageMonths: Int): List<Item> {
        val bucket = AGE_BUCKETS.lastOrNull { it <= ageMonths } ?: return emptyList()
        return ITEMS.filter { it.ageMonths == bucket }
    }

    data class FlagReport(val red: Int, val items: List<Item>, val rationale: String)

    fun evaluate(ageMonths: Int, unmetItemIds: Set<String>): FlagReport {
        val items = forAge(ageMonths).filter { it.id in unmetItemIds && it.redFlagWhenAbsent }
        return FlagReport(
            red = items.size,
            items = items,
            rationale = if (items.isEmpty())
                "Nothing flagged for this age bucket."
            else
                "${items.size} red-flag item(s) unmet at ${ageMonths}mo - share with your clinician at the next well-child visit."
        )
    }
}
