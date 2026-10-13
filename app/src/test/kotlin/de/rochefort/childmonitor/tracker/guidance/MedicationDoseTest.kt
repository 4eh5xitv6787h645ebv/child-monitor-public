package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MedicationDoseTest {

    @Test fun `paracetamol gated under 2 months`() {
        val ctx = MedicationDose.Context(ageMonths = 1, weightKg = 5.0,
            weightMeasuredDaysAgo = 10, last24hDosesMg = 0.0, last24hDoseCount = 0,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.PARACETAMOL, context = ctx)
        assertTrue(r.blocked)
    }

    @Test fun `ibuprofen gated under 5kg`() {
        val ctx = MedicationDose.Context(ageMonths = 5, weightKg = 4.5,
            weightMeasuredDaysAgo = 5, last24hDosesMg = 0.0, last24hDoseCount = 0,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.IBUPROFEN, context = ctx)
        assertTrue(r.blocked)
    }

    @Test fun `paracetamol computes 15mgPerKg`() {
        val ctx = MedicationDose.Context(ageMonths = 8, weightKg = 8.0,
            weightMeasuredDaysAgo = 3, last24hDosesMg = 0.0, last24hDoseCount = 0,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.PARACETAMOL, context = ctx)
        assertFalse(r.blocked)
        assertNotNull(r.doseMg)
        assertTrue(r.doseMg!! in 100.0..130.0)
    }

    @Test fun `aspirin is always blocked`() {
        val ctx = MedicationDose.Context(ageMonths = 60, weightKg = 20.0,
            weightMeasuredDaysAgo = 0, last24hDosesMg = 0.0, last24hDoseCount = 0,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.ASPIRIN, context = ctx)
        assertTrue(r.blocked)
    }

    @Test fun `within interval blocks next dose`() {
        val now = 10L * 3600_000L
        val ctx = MedicationDose.Context(ageMonths = 12, weightKg = 10.0,
            weightMeasuredDaysAgo = 0, last24hDosesMg = 150.0, last24hDoseCount = 1,
            lastDoseAtMs = now - 3600_000L, nowMs = now)
        val r = MedicationDose.recommend(MedicationDose.Drug.PARACETAMOL, context = ctx)
        assertTrue(r.blocked)
    }

    @Test fun `daily cap blocks`() {
        val ctx = MedicationDose.Context(ageMonths = 12, weightKg = 10.0,
            weightMeasuredDaysAgo = 0, last24hDosesMg = 600.0, last24hDoseCount = 4,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.PARACETAMOL, context = ctx)
        assertTrue(r.blocked)
    }

    @Test fun `ibuprofen under 6 months uses 5 mg per kg not old 7_5`() {
        val ctx = MedicationDose.Context(ageMonths = 4, weightKg = 6.0,
            weightMeasuredDaysAgo = 1, last24hDosesMg = 0.0, last24hDoseCount = 0,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.IBUPROFEN, context = ctx)
        assertNotNull(r.doseMg)
        // 6 kg * 5 mg/kg = 30 mg. Must not reach 45 mg (the old 7.5 mg/kg figure).
        assertTrue(r.doseMg!! in 25.0..35.0, "expected ~30mg for 6kg under-6mo, got ${r.doseMg}")
    }

    @Test fun `ibuprofen over 6 months uses 10 mg per kg`() {
        val ctx = MedicationDose.Context(ageMonths = 9, weightKg = 8.0,
            weightMeasuredDaysAgo = 1, last24hDosesMg = 0.0, last24hDoseCount = 0,
            lastDoseAtMs = null, nowMs = 0L)
        val r = MedicationDose.recommend(MedicationDose.Drug.IBUPROFEN, context = ctx)
        assertNotNull(r.doseMg)
        assertTrue(r.doseMg!! in 70.0..90.0)
    }
}
