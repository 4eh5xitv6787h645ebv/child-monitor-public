package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #312: night-light colour parsing + brightness clamping. */
class NightLightPolicyTest {

    @Test fun parsesPresetsCaseInsensitively() {
        assertEquals(0xFFFF2200.toInt(), NightLightPolicy.parseColor("red"))
        assertEquals(0xFFFFFFFF.toInt(), NightLightPolicy.parseColor(" WHITE "))
        assertEquals(NightLightPolicy.DEFAULT_COLOR, NightLightPolicy.parseColor("amber"))
    }

    @Test fun parsesHexWithAndWithoutHash() {
        assertEquals(0xFF112233.toInt(), NightLightPolicy.parseColor("#112233"))
        assertEquals(0xFF112233.toInt(), NightLightPolicy.parseColor("112233"))
    }

    @Test fun hexIsCaseInsensitive() {
        assertEquals(NightLightPolicy.parseColor("#ff7a18"), NightLightPolicy.parseColor("#FF7A18"))
        assertEquals(0xFFABCDEF.toInt(), NightLightPolicy.parseColor("#ABCDEF"))
    }

    @Test fun forcesOpaqueAlpha() {
        // Even a transparent AARRGGBB is forced fully opaque — a night-light is never see-through.
        assertEquals(0xFF445566.toInt(), NightLightPolicy.parseColor("#00445566"))
    }

    @Test fun rejectsInvalidColor() {
        assertNull(NightLightPolicy.parseColor("notacolor"))
        assertNull(NightLightPolicy.parseColor("#12"))      // wrong length
        assertNull(NightLightPolicy.parseColor("#gggggg"))  // non-hex
        assertNull(NightLightPolicy.parseColor(""))
        assertNull(NightLightPolicy.parseColor(null))
    }

    @Test fun clampsBrightness() {
        assertEquals(0f, NightLightPolicy.clampBrightness(-1f), 0f)
        assertEquals(1f, NightLightPolicy.clampBrightness(5f), 0f)
        assertEquals(0.4f, NightLightPolicy.clampBrightness(0.4f), 0f)
    }

    @Test fun nanBrightnessFallsBackToDefault() {
        assertEquals(NightLightPolicy.DEFAULT_BRIGHTNESS, NightLightPolicy.clampBrightness(Float.NaN), 0f)
    }

    @Test fun presetsAreOpaque() {
        // every preset must have full alpha so the screen is solid
        assertTrue(NightLightPolicy.presetNames().all { (NightLightPolicy.parseColor(it)!! ushr 24) == 0xFF })
    }
}
