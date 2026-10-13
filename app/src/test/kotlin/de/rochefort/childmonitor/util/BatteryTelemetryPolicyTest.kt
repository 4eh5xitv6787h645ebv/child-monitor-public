package de.rochefort.childmonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #301 (child side): battery telemetry derivations. */
class BatteryTelemetryPolicyTest {

    @Test fun lowWhenAtOrBelowThresholdAndNotCharging() {
        assertTrue(BatteryTelemetryPolicy.isLow(10, charging = false))
        assertTrue(BatteryTelemetryPolicy.isLow(15, charging = false)) // boundary inclusive
        assertTrue(BatteryTelemetryPolicy.isLow(0, charging = false))
    }

    @Test fun notLowWhenAboveThreshold() {
        assertFalse(BatteryTelemetryPolicy.isLow(16, charging = false))
        assertFalse(BatteryTelemetryPolicy.isLow(80, charging = false))
    }

    @Test fun notLowWhenCharging() {
        assertFalse(BatteryTelemetryPolicy.isLow(5, charging = true))
    }

    @Test fun unknownLevelIsNeverLow() {
        assertFalse(BatteryTelemetryPolicy.isLow(-1, charging = false))
        assertFalse(BatteryTelemetryPolicy.isLow(Int.MIN_VALUE, charging = false))
    }

    @Test fun normalizeLevelClampsUnknownToMinusOne() {
        assertEquals(42, BatteryTelemetryPolicy.normalizeLevel(42))
        assertEquals(0, BatteryTelemetryPolicy.normalizeLevel(0))
        assertEquals(100, BatteryTelemetryPolicy.normalizeLevel(100))
        assertEquals(-1, BatteryTelemetryPolicy.normalizeLevel(-1))
        assertEquals(-1, BatteryTelemetryPolicy.normalizeLevel(101))
        assertEquals(-1, BatteryTelemetryPolicy.normalizeLevel(Int.MIN_VALUE))
    }

    @Test fun mapsChargingSource() {
        assertEquals("unknown", BatteryTelemetryPolicy.chargingSource(-1)) // sticky read failed
        assertEquals("none", BatteryTelemetryPolicy.chargingSource(0))
        assertEquals("ac", BatteryTelemetryPolicy.chargingSource(1))
        assertEquals("usb", BatteryTelemetryPolicy.chargingSource(2))
        assertEquals("wireless", BatteryTelemetryPolicy.chargingSource(4))
        assertEquals("dock", BatteryTelemetryPolicy.chargingSource(8))
        assertEquals("other", BatteryTelemetryPolicy.chargingSource(16)) // not a documented plug type
        assertEquals("other", BatteryTelemetryPolicy.chargingSource(99))
    }
}
