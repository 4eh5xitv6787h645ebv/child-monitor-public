package de.rochefort.childmonitor.tracker

import de.rochefort.childmonitor.reliability.MonotonicClock
import org.json.JSONObject

/**
 * One row in the tracker event store.
 *
 * The store is the same SQLite database used by the monitor event timeline.
 * Every recommendation or threshold that the UI surfaces is anchored back to an
 * entry in [docs/research/baby-tracker-evidence-base.md], which cites WHO,
 * CDC, NHS, NICE, AAP, ASCIA, NIAID, Red Nose, Lullaby Trust, and
 * RCPCH-WHO primary sources.
 */
data class TrackerEvent(
    val id: Long = 0,
    val childId: String,
    val kind: Kind,
    val subkind: String? = null,
    val tsWallMs: Long = System.currentTimeMillis(),
    val tsMonoMs: Long = MonotonicClock.nowMs(),
    val startedMs: Long? = null,
    val endedMs: Long? = null,
    val valueNum: Double? = null,
    val valueNum2: Double? = null,
    val extra: JSONObject? = null,
    val source: Source = Source.USER_ENTRY,
    /**
     * Free-form parent notes. Stored and rendered as plain text only; never
     * pass this into `Html.fromHtml` — parents may paste URLs or symbols that
     * should not be parsed as HTML (CM-TRK-CR-P2-3).
     */
    val note: String? = null,
    val traceId: String? = null
) {

    enum class Kind {
        // Feeding
        FEED_BREAST, FEED_BOTTLE, FEED_PUMP, FEED_SOLID, FEED_ALLERGEN,
        // Elimination
        DIAPER_WET, DIAPER_DIRTY, DIAPER_MIXED,
        // Sleep
        SLEEP_START, SLEEP_END, NAP_START, NAP_END,
        // Growth
        GROWTH_WEIGHT, GROWTH_LENGTH, GROWTH_HEAD, GROWTH_BMI, GROWTH_MUAC,
        // Development
        MILESTONE,
        // Health
        VACCINE, MEDICATION, TEMPERATURE, ILLNESS,
        // Activity
        ACTIVITY_TUMMY, ACTIVITY_OUTDOOR, ACTIVITY_SCREEN, ACTIVITY_PLAY,
        // Misc
        NOTE, PHOTO
    }

    enum class Source {
        USER_ENTRY,
        DEVICE_MONITOR,
        DEVICE_TRACKER,
        IMPORT
    }

    /**
     * Feed subkinds. Stored in the `subkind` column so a single `FEED_BREAST`
     * kind can distinguish left/right/combined sessions.
     */
    object FeedSubkind {
        const val BREAST_LEFT = "BREAST_L"
        const val BREAST_RIGHT = "BREAST_R"
        const val BREAST_BOTH = "BREAST_BOTH"
        const val BOTTLE_EBM = "BOTTLE_EBM" // expressed breast milk
        const val BOTTLE_FORMULA = "BOTTLE_FORMULA"
        const val BOTTLE_COW = "BOTTLE_COW"
        const val PUMP_LEFT = "PUMP_L"
        const val PUMP_RIGHT = "PUMP_R"
        const val PUMP_BOTH = "PUMP_BOTH"
        const val SOLID_SPOON = "SOLID_SPOON"
        const val SOLID_BLW = "SOLID_BLW"
    }

    object DiaperSubkind {
        const val WET = "WET"
        const val DIRTY = "DIRTY"
        const val MIXED = "MIXED"
    }

    object SleepSubkind {
        const val NIGHT = "NIGHT"
        const val NAP = "NAP"
        const val CONTACT = "CONTACT"
        const val CAR = "CAR"
    }

    object AllergenSubkind {
        // Eight major allergens (US Big 9 minus sesame which was added in 2023)
        const val PEANUT = "PEANUT"
        const val EGG = "EGG"
        const val MILK = "MILK"
        const val WHEAT = "WHEAT"
        const val SOY = "SOY"
        const val SESAME = "SESAME"
        const val TREE_NUT = "TREE_NUT"
        const val FISH = "FISH"
        const val SHELLFISH = "SHELLFISH"
    }
}
