package de.rochefort.childmonitor.tracker.guidance

import de.rochefort.childmonitor.tracker.ChildProfile
import java.util.concurrent.TimeUnit

/**
 * Country-specific childhood immunisation schedules (0-24 months) with a next-due
 * calculator. We keep the schedule hand-curated and versioned; machine-readable
 * upstream feeds don't exist as JSON APIs.
 *
 * Sources:
 *  - CDC US: https://www.cdc.gov/vaccines/hcp/imz-schedules/child-adolescent-age.html
 *  - NHS UK: https://www.nhs.uk/vaccinations/nhs-vaccinations-and-when-to-have-them/
 *  - Australia NIP: https://www.health.gov.au/topics/immunisation/when-to-get-vaccinated/national-immunisation-program-schedule
 */
object VaccineSchedule {

    data class Dose(
        val antigen: String,
        val doseNumber: Int,
        val recommendedAgeMonths: Int,
        val notes: String = "",
        // [#244] Hard upper age limit for age-capped vaccines (notably rotavirus, whose series
        // must not start at/after 15 weeks and must not be given past ~8 months, for the
        // age-related intussusception-risk rationale). null = no upper cap.
        val maxAgeWeeks: Int? = null
    )

    private val US: List<Dose> = listOf(
        Dose("HepB", 1, 0, "birth dose"),
        Dose("HepB", 2, 1),
        Dose("DTaP", 1, 2),
        Dose("Hib", 1, 2),
        Dose("PCV", 1, 2),
        Dose("IPV", 1, 2),
        Dose("RV", 1, 2, maxAgeWeeks = 15),
        Dose("DTaP", 2, 4),
        Dose("Hib", 2, 4),
        Dose("PCV", 2, 4),
        Dose("IPV", 2, 4),
        Dose("RV", 2, 4, maxAgeWeeks = 32),
        Dose("DTaP", 3, 6),
        Dose("HepB", 3, 6),
        Dose("IPV", 3, 6),
        Dose("PCV", 3, 6),
        Dose("Influenza", 1, 6, "annual"),
        Dose("MMR", 1, 12),
        Dose("Varicella", 1, 12),
        Dose("Hib", 3, 12, "booster"),
        Dose("PCV", 4, 12),
        Dose("HepA", 1, 12),
        Dose("DTaP", 4, 15),
        Dose("HepA", 2, 18)
    )

    private val UK: List<Dose> = listOf(
        Dose("6-in-1", 1, 2),
        Dose("Rotavirus", 1, 2, maxAgeWeeks = 15),
        Dose("MenB", 1, 2),
        Dose("6-in-1", 2, 3),
        Dose("Rotavirus", 2, 3, maxAgeWeeks = 24),
        Dose("PCV", 1, 3),
        Dose("6-in-1", 3, 4),
        Dose("MenB", 2, 4),
        Dose("MMRV", 1, 12, "births on/after 1 Jan 2025"),
        Dose("PCV", 2, 12),
        Dose("MenB", 3, 12),
        Dose("HibMenC", 1, 12),
        Dose("Influenza", 1, 24, "annual")
    )

    private val AU: List<Dose> = listOf(
        Dose("HepB", 1, 0, "birth dose"),
        Dose("DTPa-IPV-HepB-Hib", 1, 2),
        Dose("PCV13", 1, 2),
        Dose("Rotavirus", 1, 2, maxAgeWeeks = 15),
        Dose("DTPa-IPV-HepB-Hib", 2, 4),
        Dose("PCV13", 2, 4),
        Dose("Rotavirus", 2, 4, maxAgeWeeks = 24),
        Dose("DTPa-IPV-HepB-Hib", 3, 6),
        Dose("PCV13", 3, 6),
        Dose("Influenza", 1, 6, "annual"),
        Dose("MenACWY", 1, 12),
        Dose("MMR", 1, 12),
        Dose("PCV13", 4, 12, "ATSI only"),
        Dose("MMRV", 1, 18),
        Dose("DTPa", 4, 18),
        Dose("Hib", 4, 18)
    )

    data class Given(val antigen: String, val doseNumber: Int, val atMs: Long)

    data class NextDue(
        val dose: Dose,
        val dueAtMs: Long,
        val overdueByDays: Int,
        // [#244] true when the child is past dose.maxAgeWeeks: the dose is no longer
        // administrable and must be surfaced as "window passed", not "overdue".
        val windowClosed: Boolean = false
    )

    fun scheduleFor(profile: ChildProfile): List<Dose> = when (profile.schedule) {
        ChildProfile.Jurisdiction.US -> US
        ChildProfile.Jurisdiction.UK -> UK
        ChildProfile.Jurisdiction.AU -> AU
        ChildProfile.Jurisdiction.WHO -> US // WHO global baseline ~ CDC for 0-24mo
    }

    fun nextDue(profile: ChildProfile, given: List<Given>, nowMs: Long): List<NextDue> {
        val schedule = scheduleFor(profile)
        val givenSet = given.map { it.antigen to it.doseNumber }.toSet()
        val dob = profile.dateOfBirthMs
        return schedule.filter { (it.antigen to it.doseNumber) !in givenSet }
            .map { dose ->
                // Calendar-month arithmetic so a "2 month" vaccine falls on the
                // 2-month birthday, not day 60.
                val dueAt = addMonthsToEpochMs(dob, dose.recommendedAgeMonths)
                val overdueByDays = ((nowMs - dueAt) / (24 * 3600_000L)).toInt().coerceAtLeast(-999)
                // [#244] An age-capped dose (e.g. rotavirus) is no longer administrable once the
                // child reaches maxAgeWeeks; flag it so callers surface "window passed", not "overdue".
                val windowClosed = dose.maxAgeWeeks?.let { maxWeeks ->
                    nowMs >= dob + maxWeeks * 7L * 24 * 3600_000L
                } ?: false
                NextDue(dose, dueAt, overdueByDays, windowClosed)
            }
            .sortedBy { it.dueAtMs }
    }

    private fun addMonthsToEpochMs(anchorMs: Long, months: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = anchorMs
        cal.add(java.util.Calendar.MONTH, months)
        return cal.timeInMillis
    }

    fun minIntervalBetweenDosesDays(antigen: String): Int = when (antigen) {
        "DTaP" -> 28
        "HepB" -> 28
        "Hib" -> 28
        "PCV", "PCV13" -> 28
        "IPV" -> 28
        "RV", "Rotavirus" -> 28
        "MMR", "MMRV" -> 28
        "Varicella" -> 90
        "HepA" -> 180
        else -> 28
    }
}
