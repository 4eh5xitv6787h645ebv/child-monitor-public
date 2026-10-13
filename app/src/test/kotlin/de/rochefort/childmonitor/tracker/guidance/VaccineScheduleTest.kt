package de.rochefort.childmonitor.tracker.guidance

import de.rochefort.childmonitor.tracker.ChildProfile
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaccineScheduleTest {

    private fun newborn(): ChildProfile {
        val now = System.currentTimeMillis()
        return ChildProfile(
            id = "p1",
            name = "Bub",
            dateOfBirthMs = now - TimeUnit.DAYS.toMillis(60),
            schedule = ChildProfile.Jurisdiction.US
        )
    }

    @Test fun `schedule for US returns non-empty`() {
        assertTrue(VaccineSchedule.scheduleFor(newborn()).isNotEmpty())
    }

    @Test fun `nextDue excludes already-given`() {
        val profile = newborn()
        val given = listOf(
            VaccineSchedule.Given("HepB", 1, profile.dateOfBirthMs),
            VaccineSchedule.Given("DTaP", 1, profile.dateOfBirthMs + TimeUnit.DAYS.toMillis(60))
        )
        val due = VaccineSchedule.nextDue(profile, given, System.currentTimeMillis())
        assertTrue(due.none { it.dose.antigen == "HepB" && it.dose.doseNumber == 1 })
        assertTrue(due.none { it.dose.antigen == "DTaP" && it.dose.doseNumber == 1 })
    }

    @Test fun `min interval is at least 28 days for core antigens`() {
        assertEquals(28, VaccineSchedule.minIntervalBetweenDosesDays("DTaP"))
        assertEquals(28, VaccineSchedule.minIntervalBetweenDosesDays("Hib"))
        assertEquals(90, VaccineSchedule.minIntervalBetweenDosesDays("Varicella"))
    }

    @Test fun `rotavirus dose is window-closed once the child passes the age cap`() {
        val now = System.currentTimeMillis()
        val older = ChildProfile(
            id = "p2", name = "Bub",
            dateOfBirthMs = now - TimeUnit.DAYS.toMillis(150), // ~21 weeks, past the 15-week cap
            schedule = ChildProfile.Jurisdiction.UK
        )
        val due = VaccineSchedule.nextDue(older, given = emptyList(), nowMs = now)
        val rv1 = due.first { it.dose.antigen == "Rotavirus" && it.dose.doseNumber == 1 }
        assertTrue(rv1.windowClosed)
    }

    @Test fun `young infant rotavirus is open and uncapped doses never close`() {
        val now = System.currentTimeMillis()
        val young = ChildProfile(
            id = "p3", name = "Bub",
            dateOfBirthMs = now - TimeUnit.DAYS.toMillis(56), // 8 weeks
            schedule = ChildProfile.Jurisdiction.UK
        )
        val due = VaccineSchedule.nextDue(young, given = emptyList(), nowMs = now)
        assertTrue(!due.first { it.dose.antigen == "Rotavirus" && it.dose.doseNumber == 1 }.windowClosed)
        // A dose with no cap (e.g. 6-in-1) is never window-closed.
        assertTrue(!due.first { it.dose.antigen == "6-in-1" && it.dose.doseNumber == 1 }.windowClosed)
    }
}
