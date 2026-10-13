package de.rochefort.childmonitor.tracker

import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChildProfileTest {

    @Test fun `term infant is not preterm`() {
        val now = System.currentTimeMillis()
        val profile = ChildProfile(
            id = "1", name = "A",
            dateOfBirthMs = now - TimeUnit.DAYS.toMillis(30),
            dueDateMs = now - TimeUnit.DAYS.toMillis(33)
        )
        assertFalse(profile.isPreterm())
    }

    @Test fun `preterm infant correction subtracts offset`() {
        val now = 0L
        val dob = -TimeUnit.DAYS.toMillis(60)
        // due 42 days after dob means born 42 days early (~34 weeks GA)
        val due = dob + TimeUnit.DAYS.toMillis(42)
        val profile = ChildProfile(id = "1", name = "A", dateOfBirthMs = dob, dueDateMs = due)
        assertTrue(profile.isPreterm())
        val chrono = profile.chronologicalAgeDays(now)
        val corrected = profile.correctedAgeDays(now)
        assertTrue(chrono > corrected)
        assertEquals(chrono - 42, corrected)
    }

    @Test fun `json round trip preserves fields`() {
        val profile = ChildProfile(
            id = "id",
            name = "Baby",
            dateOfBirthMs = 1234567890L,
            dueDateMs = 9876543210L,
            sex = ChildProfile.Sex.FEMALE,
            allergyRiskTier = ChildProfile.AllergyRiskTier.HIGH,
            schedule = ChildProfile.Jurisdiction.UK,
            feedingMode = ChildProfile.FeedingMode.BREAST
        )
        val rebuilt = ChildProfile.fromJson(profile.toJson())!!
        assertEquals(profile, rebuilt)
    }
}
