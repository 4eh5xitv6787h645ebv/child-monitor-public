package de.rochefort.childmonitor.tracker

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Per-child profile used to evaluate age-dependent thresholds and corrected ages.
 *
 * All age math uses [MonotonicClock]-compatible millisecond epoch values so it stays
 * consistent across wall-clock changes. Prematurity correction applies up to 24 months
 * chronological, per the [RCPCH UK-WHO growth charts](https://www.rcpch.ac.uk/resources/uk-who-growth-charts-0-4-years)
 * and AAP Bright Futures practice.
 */
data class ChildProfile(
    val id: String,
    val name: String,
    val dateOfBirthMs: Long,
    val dueDateMs: Long? = null,
    val sex: Sex = Sex.UNKNOWN,
    val allergyRiskTier: AllergyRiskTier = AllergyRiskTier.LOW,
    val familyAllergyHistory: Set<String> = emptySet(),
    val schedule: Jurisdiction = Jurisdiction.US,
    val feedingMode: FeedingMode = FeedingMode.UNDECIDED
) {
    enum class Sex { MALE, FEMALE, UNKNOWN }
    enum class AllergyRiskTier { HIGH, MODERATE, LOW }
    enum class Jurisdiction { US, UK, AU, WHO }
    enum class FeedingMode { BREAST, FORMULA, MIXED, UNDECIDED }

    val gestationalAgeWeeksAtBirth: Int?
        get() {
            val due = dueDateMs ?: return null
            val diffDays = TimeUnit.MILLISECONDS.toDays(due - dateOfBirthMs).toInt()
            val weeks = 40 - diffDays / 7
            return weeks.takeIf { it in 20..45 }
        }

    fun isPreterm(): Boolean = (gestationalAgeWeeksAtBirth ?: 40) < 37

    fun chronologicalAgeDays(nowMs: Long): Int =
        TimeUnit.MILLISECONDS.toDays(nowMs - dateOfBirthMs).toInt().coerceAtLeast(0)

    /**
     * Calendar-based age in whole months. A baby born 2025-01-15 is 2 months old
     * on 2025-03-15 (not day 60). Avoids the `days / 30` drift that mis-buckets
     * 2-/4-/6-month visits by several days per year.
     */
    fun chronologicalAgeMonths(nowMs: Long): Int = calendarMonthsBetween(dateOfBirthMs, nowMs)

    /**
     * Corrected age in days. For preterm infants, subtracts the premature delivery
     * offset from chronological age until 24 months chronological. After that we use
     * chronological age (standard pediatric practice).
     */
    fun correctedAgeDays(nowMs: Long): Int {
        val chrono = chronologicalAgeDays(nowMs)
        if (!isPreterm() || dueDateMs == null) return chrono
        if (chronologicalAgeMonths(nowMs) > 24) return chrono
        val offsetDays = TimeUnit.MILLISECONDS.toDays(dueDateMs - dateOfBirthMs).toInt()
        return (chrono - offsetDays).coerceAtLeast(0)
    }

    fun correctedAgeMonths(nowMs: Long): Int {
        val chronoMonths = chronologicalAgeMonths(nowMs)
        if (!isPreterm() || dueDateMs == null) return chronoMonths
        if (chronoMonths > 24) return chronoMonths
        val correctedAnchor = dateOfBirthMs + (dueDateMs - dateOfBirthMs)
        val corrected = calendarMonthsBetween(correctedAnchor, nowMs)
        return corrected.coerceAtLeast(0)
    }

    private fun calendarMonthsBetween(fromMs: Long, toMs: Long): Int {
        if (toMs < fromMs) return 0
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = fromMs
        val fromYear = cal.get(java.util.Calendar.YEAR)
        val fromMonth = cal.get(java.util.Calendar.MONTH)
        val fromDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
        cal.timeInMillis = toMs
        val toYear = cal.get(java.util.Calendar.YEAR)
        val toMonth = cal.get(java.util.Calendar.MONTH)
        val toDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
        var months = (toYear - fromYear) * 12 + (toMonth - fromMonth)
        if (toDay < fromDay) months -= 1
        return months.coerceAtLeast(0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("dateOfBirthMs", dateOfBirthMs)
        .put("dueDateMs", dueDateMs ?: JSONObject.NULL)
        .put("sex", sex.name)
        .put("allergyRiskTier", allergyRiskTier.name)
        .put("familyAllergyHistory", org.json.JSONArray(familyAllergyHistory.toList()))
        .put("schedule", schedule.name)
        .put("feedingMode", feedingMode.name)

    companion object {
        fun fromJson(json: JSONObject): ChildProfile? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = json.optString("name", "Baby")
            val dob = json.optLong("dateOfBirthMs", 0L).takeIf { it > 0 } ?: return null
            val due = json.optLong("dueDateMs", 0L).takeIf { it > 0 }
            val sex = runCatching { Sex.valueOf(json.optString("sex", "UNKNOWN")) }.getOrDefault(Sex.UNKNOWN)
            val tier = runCatching { AllergyRiskTier.valueOf(json.optString("allergyRiskTier", "LOW")) }.getOrDefault(AllergyRiskTier.LOW)
            val history = buildSet {
                val arr = json.optJSONArray("familyAllergyHistory")
                if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
            }
            val jur = runCatching { Jurisdiction.valueOf(json.optString("schedule", "US")) }.getOrDefault(Jurisdiction.US)
            val mode = runCatching { FeedingMode.valueOf(json.optString("feedingMode", "UNDECIDED")) }.getOrDefault(FeedingMode.UNDECIDED)
            return ChildProfile(id, name, dob, due, sex, tier, history, jur, mode)
        }
    }
}
