package de.rochefort.childmonitor.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #357: per-effect alert model + migration from the old single AlertStyle pref. */
class AlertEffectsPolicyTest {

    @Test fun `default is audible only`() {
        assertEquals(AlertEffects(sound = true, vibration = false, screenFlash = false, torch = false),
            AlertEffectsPolicy.DEFAULT)
    }

    @Test fun `migrate normal-null-unknown to default`() {
        assertEquals(AlertEffectsPolicy.DEFAULT, AlertEffectsPolicy.migrate(null))
        assertEquals(AlertEffectsPolicy.DEFAULT, AlertEffectsPolicy.migrate("normal"))
        assertEquals(AlertEffectsPolicy.DEFAULT, AlertEffectsPolicy.migrate("garbage"))
    }

    @Test fun `migrate vibration-only style`() {
        val e = AlertEffectsPolicy.migrate("vibration_only")
        assertFalse(e.sound); assertTrue(e.vibration); assertFalse(e.screenFlash); assertFalse(e.torch)
    }

    @Test fun `migrate deaf-HoH style enables all non-audible effects`() {
        val e = AlertEffectsPolicy.migrate("high_visibility")
        assertFalse(e.sound); assertTrue(e.vibration); assertTrue(e.screenFlash); assertTrue(e.torch)
        // synonyms
        assertEquals(e, AlertEffectsPolicy.migrate("deaf_hoh"))
        assertEquals(e, AlertEffectsPolicy.migrate("HOH"))
    }

    @Test fun `any reflects whether at least one effect is on`() {
        assertFalse(AlertEffects(false, false, false, false).any())
        assertTrue(AlertEffects(false, false, true, false).any())
        assertTrue(AlertEffectsPolicy.DEFAULT.any())
    }

    @Test fun `anyNonAudible excludes sound`() {
        assertFalse(AlertEffects(sound = true, vibration = false, screenFlash = false, torch = false).anyNonAudible())
        assertTrue(AlertEffects(sound = true, vibration = true, screenFlash = false, torch = false).anyNonAudible())
        assertTrue(AlertEffects(sound = false, vibration = false, screenFlash = true, torch = false).anyNonAudible())
    }
}
