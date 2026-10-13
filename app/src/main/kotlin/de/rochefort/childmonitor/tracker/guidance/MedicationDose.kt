package de.rochefort.childmonitor.tracker.guidance

import kotlin.math.max
import kotlin.math.min

/**
 * Weight-based paracetamol and ibuprofen dose calculator with safety blocks.
 *
 * Sources:
 *  - NICE NG143 §1.6: https://www.nice.org.uk/guidance/ng143/chapter/Recommendations
 *  - NHS paracetamol for children: https://www.nhs.uk/medicines/paracetamol-for-children/
 *  - NHS fever in children: https://www.nhs.uk/conditions/fever-in-children/
 *  - BNFc (cited via NICE NG143 references for dose caps).
 *
 * The computations intentionally return suggestions; the parent is still entering what
 * they actually gave. Blocks fire for known hard limits (min age/weight, 24h cap,
 * interval, aspirin under 16).
 */
object MedicationDose {

    enum class Drug { PARACETAMOL, IBUPROFEN, ASPIRIN }

    data class Spec(
        val drug: Drug,
        val intervalHours: Int,
        val dailyCapPerKgMg: Double,
        val minAgeMonths: Int,
        val minWeightKg: Double,
        val maxSingleDoseMg: Double,
        /**
         * Maximum number of doses per 24h, separate from the mg/kg/24h cap. NICE
         * paracetamol guidance is q4-6h with no more than 4 doses in 24h. NHS
         * ibuprofen for children is q6-8h with no more than 3-4 doses in 24h —
         * we use 3 to stay on the conservative side (matches NHS wording 'do not
         * give more than 3 doses in 24h' for under-12-month dosing).
         * Was hard-coded to 4-4 in the function body (#192-5).
         */
        val maxDosesPerDay: Int,
        /**
         * Age-banded dose in mg/kg. NICE NG143 + NHS + BNFc tables are age-banded;
         * for paracetamol the 10-15 mg/kg range is flat across infancy, for ibuprofen
         * the dose per kg is lower under 6 months. See callers for citation.
         */
        val mgPerKgForAge: (Int) -> Double
    ) {
        companion object {
            // Paracetamol: NICE 10-15 mg/kg q4-6h, max 60 mg/kg/24h, max 4 doses/24h.
            // We use 15 mg/kg up to the single-dose cap.
            // Source: https://www.nice.org.uk/guidance/ng143/chapter/Recommendations
            val PARACETAMOL = Spec(
                drug = Drug.PARACETAMOL,
                intervalHours = 4,
                dailyCapPerKgMg = 60.0,
                minAgeMonths = 2,
                minWeightKg = 0.0,
                maxSingleDoseMg = 1_000.0,
                maxDosesPerDay = 4,
                mgPerKgForAge = { _ -> 15.0 }
            )
            // Ibuprofen is age-banded. NHS fever-in-children table:
            //   3-5 months: ~5 mg/kg/dose (2.5 mL of 100 mg/5 mL = 50 mg for ~6 kg).
            //   6 months+: up to 10 mg/kg/dose.
            // Max 30 mg/kg/24h; max 3 doses/24h (NHS conservative); earliest
            // allowed at 3 months AND >= 5 kg.
            // Source: https://www.nhs.uk/conditions/fever-in-children/
            val IBUPROFEN = Spec(
                drug = Drug.IBUPROFEN,
                intervalHours = 6,
                dailyCapPerKgMg = 30.0,
                minAgeMonths = 3,
                minWeightKg = 5.0,
                maxSingleDoseMg = 400.0,
                maxDosesPerDay = 3,
                mgPerKgForAge = { ageMonths -> if (ageMonths < 6) 5.0 else 10.0 }
            )
        }
    }

    data class Recommendation(
        val drug: Drug,
        val doseMg: Double?,
        val nextSafeDoseAtMs: Long?,
        val blocked: Boolean,
        val blockedReasons: List<String>,
        val warnings: List<String>,
        val source: String
    )

    data class Context(
        val ageMonths: Int,
        val weightKg: Double,
        val weightMeasuredDaysAgo: Int?,
        val last24hDosesMg: Double,
        val last24hDoseCount: Int,
        val lastDoseAtMs: Long?,
        val nowMs: Long,
        /**
         * #147-2: timestamp of the most recent dose of the OTHER analgesic in
         * the alternation pair (paracetamol ↔ ibuprofen). NICE NG143 §1.6.6
         * permits alternating the two, but stacking them <60min apart is
         * discouraged because parents lose track of which they last gave and
         * over-dose risk climbs. Pass null when the parent isn't using
         * alternation (e.g., ibuprofen-only or no prior dose).
         */
        val lastOtherDrugDoseAtMs: Long? = null
    )

    fun recommend(drug: Drug, spec: Spec = defaultSpec(drug), context: Context): Recommendation {
        val blocks = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (drug == Drug.ASPIRIN) {
            blocks += "Aspirin is contraindicated in children under 16 (Reye's syndrome). NHS."
            return Recommendation(drug, null, null, true, blocks, warnings,
                source = "https://www.nhs.uk/conditions/reyes-syndrome/")
        }

        if (context.ageMonths < spec.minAgeMonths) {
            blocks += "${drug.name} requires age >= ${spec.minAgeMonths} months."
        }
        if (spec.minWeightKg > 0 && context.weightKg < spec.minWeightKg) {
            blocks += "${drug.name} requires weight >= ${spec.minWeightKg} kg."
        }
        if (context.weightKg <= 0) {
            blocks += "Recorded weight is required for per-kg dosing."
        }
        if (context.weightMeasuredDaysAgo != null && context.weightMeasuredDaysAgo > 30) {
            warnings += "Weight was measured ${context.weightMeasuredDaysAgo} days ago. Re-weigh for accurate dosing."
        }

        val mgPerKg = spec.mgPerKgForAge(context.ageMonths)
        val desiredMg = min(spec.maxSingleDoseMg, context.weightKg * mgPerKg)
        val dailyCapMg = context.weightKg * spec.dailyCapPerKgMg
        if (context.last24hDosesMg + desiredMg > dailyCapMg) {
            blocks += "Would exceed 24h cap of ${String.format(java.util.Locale.US, "%.0f", dailyCapMg)} mg. Already given ${String.format(java.util.Locale.US, "%.0f", context.last24hDosesMg)} mg in the last 24 hours."
        }

        val intervalMs = spec.intervalHours * 3600L * 1000L
        val nextOk = context.lastDoseAtMs?.let { it + intervalMs }
        if (nextOk != null && nextOk > context.nowMs) {
            val mins = max(0, (nextOk - context.nowMs) / 60_000L)
            blocks += "Too soon. Next dose allowed in $mins minutes."
        }

        // #147-2 interleave guard: if the other analgesic was given <60 min
        // ago, warn (don't block — alternation IS legitimate per NICE). Parents
        // commonly forget which they last administered and stack them in a
        // panic during a high fever.
        // Use absolute value of the delta so clock skew (NTP rewind, DST end,
        // test mocks, cross-device sync where the dose-logging phone had a
        // faster clock) doesn't fail-open and suppress the warning.
        val INTERLEAVE_WARN_MS = 60L * 60_000L
        val msSinceOther = context.lastOtherDrugDoseAtMs?.let { kotlin.math.abs(context.nowMs - it) }
        if (msSinceOther != null && msSinceOther < INTERLEAVE_WARN_MS) {
            val mins = msSinceOther / 60_000L
            val otherName = if (drug == Drug.PARACETAMOL) "ibuprofen" else "paracetamol"
            warnings += "Alternating? $otherName was given $mins min ago. Wait at least 60 min between drugs to avoid stacking."
        }

        // #192-5: was 'if (drug == PARACETAMOL) 4 else 4' which was both a dead
        // branch and the wrong cap for ibuprofen (NHS conservative is 3, not 4).
        // Now pulled from Spec.maxDosesPerDay.
        if (context.last24hDoseCount >= spec.maxDosesPerDay) {
            blocks += "Already hit the ${spec.maxDosesPerDay}-dose daily limit."
        }

        return Recommendation(
            drug = drug,
            doseMg = desiredMg.takeIf { blocks.isEmpty() },
            nextSafeDoseAtMs = nextOk,
            blocked = blocks.isNotEmpty(),
            blockedReasons = blocks,
            warnings = warnings,
            source = when (drug) {
                Drug.PARACETAMOL -> "https://www.nhs.uk/medicines/paracetamol-for-children/"
                Drug.IBUPROFEN -> "https://www.nhs.uk/conditions/fever-in-children/"
                Drug.ASPIRIN -> "https://www.nhs.uk/conditions/reyes-syndrome/"
            }
        )
    }

    /** Converts an mg dose into a mL volume for the strength printed on the syringe. */
    fun doseMlFromMg(doseMg: Double, strengthMgPer5Ml: Double): Double {
        if (strengthMgPer5Ml <= 0) return 0.0
        val mgPerMl = strengthMgPer5Ml / 5.0
        return doseMg / mgPerMl
    }

    private fun defaultSpec(drug: Drug): Spec = when (drug) {
        Drug.PARACETAMOL -> Spec.PARACETAMOL
        Drug.IBUPROFEN -> Spec.IBUPROFEN
        Drug.ASPIRIN -> Spec(
            drug = Drug.ASPIRIN,
            intervalHours = 24,
            dailyCapPerKgMg = 0.0,
            minAgeMonths = 192,  // 16 years
            minWeightKg = 0.0,
            maxSingleDoseMg = 0.0,
            maxDosesPerDay = 0,  // Reye's-syndrome block; spec is never actually used
            mgPerKgForAge = { _ -> 0.0 }
        )
    }
}
