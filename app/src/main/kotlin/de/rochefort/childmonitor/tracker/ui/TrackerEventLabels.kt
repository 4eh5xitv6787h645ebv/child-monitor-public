package de.rochefort.childmonitor.tracker.ui

import android.content.Context
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.TrackerEvent
import java.util.Locale

/** One resource-backed vocabulary for tracker rows, editor choices, and TalkBack speech (#484). */
object TrackerEventLabels {
    data class SubtypeChoice(val storedValue: String, val label: String)

    fun event(context: Context, event: TrackerEvent): String {
        val base = kind(context, event.kind)
        val subtype = event.subkind?.takeIf { it.isNotBlank() }?.let { subtype(context, it) }
        return if (subtype == null) base else context.getString(R.string.tracker_event_with_subtype, base, subtype)
    }

    fun kind(context: Context, kind: TrackerEvent.Kind): String = context.getString(
        when (kind) {
            TrackerEvent.Kind.FEED_BREAST -> R.string.tracker_kind_feed_breast
            TrackerEvent.Kind.FEED_BOTTLE -> R.string.tracker_kind_feed_bottle
            TrackerEvent.Kind.FEED_PUMP -> R.string.tracker_kind_feed_pump
            TrackerEvent.Kind.FEED_SOLID -> R.string.tracker_kind_feed_solid
            TrackerEvent.Kind.FEED_ALLERGEN -> R.string.tracker_kind_feed_allergen
            TrackerEvent.Kind.DIAPER_WET -> R.string.tracker_kind_diaper_wet
            TrackerEvent.Kind.DIAPER_DIRTY -> R.string.tracker_kind_diaper_dirty
            TrackerEvent.Kind.DIAPER_MIXED -> R.string.tracker_kind_diaper_mixed
            TrackerEvent.Kind.SLEEP_START -> R.string.tracker_kind_sleep_start
            TrackerEvent.Kind.SLEEP_END -> R.string.tracker_kind_sleep_end
            TrackerEvent.Kind.NAP_START -> R.string.tracker_kind_nap_start
            TrackerEvent.Kind.NAP_END -> R.string.tracker_kind_nap_end
            TrackerEvent.Kind.GROWTH_WEIGHT -> R.string.tracker_kind_growth_weight
            TrackerEvent.Kind.GROWTH_LENGTH -> R.string.tracker_kind_growth_length
            TrackerEvent.Kind.GROWTH_HEAD -> R.string.tracker_kind_growth_head
            TrackerEvent.Kind.GROWTH_BMI -> R.string.tracker_kind_growth_bmi
            TrackerEvent.Kind.GROWTH_MUAC -> R.string.tracker_kind_growth_muac
            TrackerEvent.Kind.MILESTONE -> R.string.tracker_kind_milestone
            TrackerEvent.Kind.VACCINE -> R.string.tracker_kind_vaccine
            TrackerEvent.Kind.MEDICATION -> R.string.tracker_kind_medication
            TrackerEvent.Kind.TEMPERATURE -> R.string.tracker_kind_temperature
            TrackerEvent.Kind.ILLNESS -> R.string.tracker_kind_illness
            TrackerEvent.Kind.ACTIVITY_TUMMY -> R.string.tracker_kind_activity_tummy
            TrackerEvent.Kind.ACTIVITY_OUTDOOR -> R.string.tracker_kind_activity_outdoor
            TrackerEvent.Kind.ACTIVITY_SCREEN -> R.string.tracker_kind_activity_screen
            TrackerEvent.Kind.ACTIVITY_PLAY -> R.string.tracker_kind_activity_play
            TrackerEvent.Kind.NOTE -> R.string.tracker_kind_note
            TrackerEvent.Kind.PHOTO -> R.string.tracker_kind_photo
        },
    )

    fun details(context: Context, event: TrackerEvent): String {
        val parts = mutableListOf<String>()
        event.valueNum?.let { number ->
            parts += when (event.kind) {
                TrackerEvent.Kind.GROWTH_WEIGHT -> context.getString(R.string.tracker_detail_weight, number)
                TrackerEvent.Kind.GROWTH_LENGTH, TrackerEvent.Kind.GROWTH_HEAD ->
                    context.getString(R.string.tracker_detail_length, number)
                TrackerEvent.Kind.MEDICATION -> context.getString(R.string.tracker_detail_medication, number)
                TrackerEvent.Kind.TEMPERATURE -> context.getString(R.string.tracker_detail_temperature, number)
                TrackerEvent.Kind.FEED_BOTTLE -> context.getString(R.string.tracker_detail_volume, number)
                TrackerEvent.Kind.FEED_ALLERGEN -> context.getString(R.string.tracker_detail_protein, number)
                else -> context.getString(R.string.tracker_detail_number, number)
            }
        }
        event.note?.takeIf { it.isNotBlank() }?.let(parts::add)
        return parts.joinToString(context.getString(R.string.tracker_detail_separator))
    }

    fun subtypeChoices(context: Context, kind: TrackerEvent.Kind): List<SubtypeChoice> {
        val storedValues = when (kind) {
            TrackerEvent.Kind.FEED_BREAST -> listOf(
                TrackerEvent.FeedSubkind.BREAST_LEFT,
                TrackerEvent.FeedSubkind.BREAST_RIGHT,
                TrackerEvent.FeedSubkind.BREAST_BOTH,
            )
            TrackerEvent.Kind.FEED_BOTTLE -> listOf(
                TrackerEvent.FeedSubkind.BOTTLE_EBM,
                TrackerEvent.FeedSubkind.BOTTLE_FORMULA,
                TrackerEvent.FeedSubkind.BOTTLE_COW,
            )
            TrackerEvent.Kind.FEED_PUMP -> listOf(
                TrackerEvent.FeedSubkind.PUMP_LEFT,
                TrackerEvent.FeedSubkind.PUMP_RIGHT,
                TrackerEvent.FeedSubkind.PUMP_BOTH,
            )
            TrackerEvent.Kind.FEED_SOLID -> listOf(
                TrackerEvent.FeedSubkind.SOLID_SPOON,
                TrackerEvent.FeedSubkind.SOLID_BLW,
            )
            TrackerEvent.Kind.FEED_ALLERGEN -> listOf(
                "PEANUT", "EGG", "MILK", "WHEAT", "SOY", "SESAME", "TREE_NUT", "FISH", "SHELLFISH",
            )
            TrackerEvent.Kind.DIAPER_DIRTY, TrackerEvent.Kind.DIAPER_MIXED -> listOf(
                "YELLOW_MUSTARD", "TAN_BROWN", "GREEN", "BLACK_MECONIUM", "BLACK_MELENA",
                "RED_BLOOD", "WHITE_PALE_CHALKY",
            )
            TrackerEvent.Kind.SLEEP_START, TrackerEvent.Kind.SLEEP_END,
            TrackerEvent.Kind.NAP_START, TrackerEvent.Kind.NAP_END -> listOf(
                "CRIB", "BASSINET", "BEDSIDE_SLEEPER", "PARENT_BED", "COUCH", "CAR_SEAT",
                "SWING", "BOUNCER", "INCLINED_SLEEPER", "OTHER",
            )
            else -> emptyList()
        }
        return storedValues.map { SubtypeChoice(it, subtype(context, it)) }
    }

    fun subtype(context: Context, stored: String): String = when (stored) {
        TrackerEvent.FeedSubkind.BREAST_LEFT -> context.getString(R.string.tracker_feed_breast_L)
        TrackerEvent.FeedSubkind.BREAST_RIGHT -> context.getString(R.string.tracker_feed_breast_R)
        TrackerEvent.FeedSubkind.BREAST_BOTH -> context.getString(R.string.tracker_feed_breast_both)
        TrackerEvent.FeedSubkind.BOTTLE_EBM -> context.getString(R.string.tracker_feed_bottle_ebm)
        TrackerEvent.FeedSubkind.BOTTLE_FORMULA -> context.getString(R.string.tracker_feed_bottle_formula)
        TrackerEvent.FeedSubkind.BOTTLE_COW -> context.getString(R.string.tracker_feed_bottle_cow)
        TrackerEvent.FeedSubkind.PUMP_LEFT -> context.getString(R.string.tracker_feed_pump_left)
        TrackerEvent.FeedSubkind.PUMP_RIGHT -> context.getString(R.string.tracker_feed_pump_right)
        TrackerEvent.FeedSubkind.PUMP_BOTH -> context.getString(R.string.tracker_feed_pump_both)
        TrackerEvent.FeedSubkind.SOLID_SPOON -> context.getString(R.string.tracker_feed_solid_spoon)
        TrackerEvent.FeedSubkind.SOLID_BLW -> context.getString(R.string.tracker_feed_solid_blw)
        "YELLOW_MUSTARD" -> context.getString(R.string.tracker_stool_yellow)
        "TAN_BROWN" -> context.getString(R.string.tracker_stool_tan)
        "GREEN" -> context.getString(R.string.tracker_stool_green)
        "BLACK_MECONIUM" -> context.getString(R.string.tracker_stool_meconium)
        "BLACK_MELENA" -> context.getString(R.string.tracker_stool_black)
        "RED_BLOOD" -> context.getString(R.string.tracker_stool_red)
        "WHITE_PALE_CHALKY" -> context.getString(R.string.tracker_stool_pale)
        "PARACETAMOL" -> context.getString(R.string.tracker_drug_paracetamol)
        "IBUPROFEN" -> context.getString(R.string.tracker_drug_ibuprofen)
        "ASPIRIN" -> context.getString(R.string.tracker_drug_aspirin)
        "PEANUT" -> context.getString(R.string.tracker_allergen_label_peanut)
        "EGG" -> context.getString(R.string.tracker_allergen_label_egg)
        "MILK" -> context.getString(R.string.tracker_allergen_label_milk)
        "WHEAT" -> context.getString(R.string.tracker_allergen_label_wheat)
        "SOY" -> context.getString(R.string.tracker_allergen_label_soy)
        "SESAME" -> context.getString(R.string.tracker_allergen_label_sesame)
        "TREE_NUT" -> context.getString(R.string.tracker_allergen_label_tree_nut)
        "FISH" -> context.getString(R.string.tracker_allergen_label_fish)
        "SHELLFISH" -> context.getString(R.string.tracker_allergen_label_shellfish)
        "CRIB" -> context.getString(R.string.tracker_sleep_location_crib)
        "BASSINET" -> context.getString(R.string.tracker_sleep_location_bassinet)
        "BEDSIDE_SLEEPER" -> context.getString(R.string.tracker_sleep_location_bedside)
        "PARENT_BED" -> context.getString(R.string.tracker_sleep_location_parent_bed)
        "COUCH" -> context.getString(R.string.tracker_sleep_location_couch)
        "CAR_SEAT" -> context.getString(R.string.tracker_sleep_location_car_seat)
        "SWING" -> context.getString(R.string.tracker_sleep_location_swing)
        "BOUNCER" -> context.getString(R.string.tracker_sleep_location_bouncer)
        "INCLINED_SLEEPER" -> context.getString(R.string.tracker_sleep_location_inclined)
        "OTHER" -> context.getString(R.string.tracker_sleep_location_other)
        else -> stored.replace('_', ' ').lowercase(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
