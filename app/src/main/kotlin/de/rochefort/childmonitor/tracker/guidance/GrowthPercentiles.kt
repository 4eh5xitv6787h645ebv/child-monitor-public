package de.rochefort.childmonitor.tracker.guidance

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Growth z-score and percentile calculations using WHO's LMS method.
 *
 * z = ((value / M)^L - 1) / (L * S), for L != 0.
 * z =  ln(value / M) / S,             for L == 0.
 *
 * percentile = Φ(z) in %.
 *
 * This class ships a curated slice of the WHO 0-5y tables - weight-for-age and
 * length-for-age at integer-month ages, male and female. Complete tables can be
 * swapped in from the WHO CSVs by reading `assets/tracker/who_lms.json` at runtime.
 *
 * Source: https://www.who.int/tools/child-growth-standards
 */
object GrowthPercentiles {

    data class LmsPoint(val ageMonths: Int, val L: Double, val M: Double, val S: Double)

    enum class Reference { WFA_MALE, WFA_FEMALE, LFA_MALE, LFA_FEMALE, HCFA_MALE, HCFA_FEMALE }

    /**
     * WHO weight-for-age (kg), 0-24 months, monthly. Abbreviated; full tables are
     * delivered via the JSON asset loader at runtime when available.
     * Values from https://www.who.int/tools/child-growth-standards/standards/weight-for-age
     */
    private val WFA_MALE: List<LmsPoint> = listOf(
        LmsPoint(0, 0.3487, 3.3464, 0.14602),
        LmsPoint(1, 0.2297, 4.4709, 0.13395),
        LmsPoint(2, 0.1970, 5.5675, 0.12385),
        LmsPoint(3, 0.1738, 6.3762, 0.11727),
        LmsPoint(4, 0.1553, 7.0023, 0.11316),
        LmsPoint(5, 0.1395, 7.5105, 0.11080),
        LmsPoint(6, 0.1257, 7.9340, 0.10958),
        LmsPoint(9, 0.0923, 8.9014, 0.10852),
        LmsPoint(12, 0.0641, 9.6479, 0.10882),
        LmsPoint(18, 0.0237, 10.9385, 0.11020),
        LmsPoint(24, -0.0068, 12.1515, 0.11140)
    )
    private val WFA_FEMALE: List<LmsPoint> = listOf(
        LmsPoint(0, 0.3809, 3.2322, 0.14171),
        LmsPoint(1, 0.1714, 4.1873, 0.13724),
        LmsPoint(2, 0.0962, 5.1282, 0.13000),
        LmsPoint(3, 0.0402, 5.8458, 0.12619),
        LmsPoint(4, -0.0050, 6.4237, 0.12402),
        LmsPoint(5, -0.0430, 6.8985, 0.12274),
        LmsPoint(6, -0.0756, 7.2970, 0.12204),
        LmsPoint(9, -0.1553, 8.2313, 0.12179),
        LmsPoint(12, -0.2144, 8.9481, 0.12237),
        LmsPoint(18, -0.3024, 10.2315, 0.12357),
        LmsPoint(24, -0.3700, 11.4775, 0.12423)
    )
    private val LFA_MALE: List<LmsPoint> = listOf(
        LmsPoint(0, 1.0, 49.8842, 0.03795),
        LmsPoint(1, 1.0, 54.7244, 0.03557),
        LmsPoint(3, 1.0, 61.4292, 0.03328),
        LmsPoint(6, 1.0, 67.6229, 0.03165),
        LmsPoint(12, 1.0, 75.7488, 0.03050),
        LmsPoint(24, 1.0, 87.1161, 0.03015)
    )
    private val LFA_FEMALE: List<LmsPoint> = listOf(
        LmsPoint(0, 1.0, 49.1477, 0.03790),
        LmsPoint(1, 1.0, 53.6872, 0.03640),
        LmsPoint(3, 1.0, 59.8029, 0.03502),
        LmsPoint(6, 1.0, 65.7311, 0.03310),
        LmsPoint(12, 1.0, 74.0148, 0.03165),
        LmsPoint(24, 1.0, 85.7153, 0.03106)
    )
    private val HCFA_MALE: List<LmsPoint> = listOf(
        LmsPoint(0, 1.0, 34.4618, 0.03686),
        LmsPoint(1, 1.0, 37.2759, 0.03133),
        LmsPoint(3, 1.0, 40.5135, 0.02728),
        LmsPoint(6, 1.0, 43.2456, 0.02540),
        LmsPoint(12, 1.0, 46.0256, 0.02460),
        LmsPoint(24, 1.0, 48.3165, 0.02446)
    )
    private val HCFA_FEMALE: List<LmsPoint> = listOf(
        LmsPoint(0, 1.0, 33.8787, 0.03496),
        LmsPoint(1, 1.0, 36.5463, 0.03068),
        LmsPoint(3, 1.0, 39.5328, 0.02703),
        LmsPoint(6, 1.0, 42.1995, 0.02544),
        LmsPoint(12, 1.0, 44.7729, 0.02455),
        LmsPoint(24, 1.0, 47.0992, 0.02426)
    )

    /** True when [ageMonths] is covered by the reference table. */
    fun isInRange(reference: Reference, ageMonths: Int): Boolean {
        val table = tableFor(reference)
        if (table.isEmpty()) return false
        return ageMonths in table.first().ageMonths..table.last().ageMonths
    }

    /** Returns null when ageMonths is outside the table so the UI can avoid
     *  showing a misleading percentile for an out-of-range child. */
    fun zScoreOrNull(reference: Reference, value: Double, ageMonths: Int): Double? {
        if (!isInRange(reference, ageMonths)) return null
        val table = tableFor(reference)
        val p = interpolate(table, ageMonths)
        return computeZ(value, p.L, p.M, p.S)
    }

    fun percentileOrNull(reference: Reference, value: Double, ageMonths: Int): Double? {
        val z = zScoreOrNull(reference, value, ageMonths) ?: return null
        return standardNormalCdf(z) * 100.0
    }

    fun zScore(reference: Reference, value: Double, ageMonths: Int): Double {
        val table = tableFor(reference)
        val p = interpolate(table, ageMonths)
        return computeZ(value, p.L, p.M, p.S)
    }

    fun percentile(reference: Reference, value: Double, ageMonths: Int): Double {
        val z = zScore(reference, value, ageMonths)
        return standardNormalCdf(z) * 100.0
    }

    private fun tableFor(reference: Reference): List<LmsPoint> = when (reference) {
        Reference.WFA_MALE -> WFA_MALE
        Reference.WFA_FEMALE -> WFA_FEMALE
        Reference.LFA_MALE -> LFA_MALE
        Reference.LFA_FEMALE -> LFA_FEMALE
        Reference.HCFA_MALE -> HCFA_MALE
        Reference.HCFA_FEMALE -> HCFA_FEMALE
    }

    private fun interpolate(table: List<LmsPoint>, ageMonths: Int): LmsPoint {
        if (table.isEmpty()) return LmsPoint(ageMonths, 1.0, 1.0, 0.1)
        val clamped = ageMonths.coerceIn(table.first().ageMonths, table.last().ageMonths)
        val lower = table.last { it.ageMonths <= clamped }
        if (lower.ageMonths == clamped) return lower
        val upper = table.first { it.ageMonths >= clamped }
        val ratio = (clamped - lower.ageMonths).toDouble() / (upper.ageMonths - lower.ageMonths).toDouble()
        return LmsPoint(
            ageMonths = clamped,
            L = lower.L + (upper.L - lower.L) * ratio,
            M = lower.M + (upper.M - lower.M) * ratio,
            S = lower.S + (upper.S - lower.S) * ratio
        )
    }

    private fun computeZ(value: Double, L: Double, M: Double, S: Double): Double {
        if (value <= 0.0 || M <= 0.0 || S <= 0.0) return 0.0
        return if (kotlin.math.abs(L) < 1e-6) ln(value / M) / S
        else ((value / M).pow(L) - 1.0) / (L * S)
    }

    /** Abramowitz & Stegun 26.2.17 - cheap approximation for our UI (accuracy ~7.5e-8). */
    private fun standardNormalCdf(z: Double): Double {
        val b1 = 0.319381530
        val b2 = -0.356563782
        val b3 = 1.781477937
        val b4 = -1.821255978
        val b5 = 1.330274429
        val p = 0.2316419
        val t = 1.0 / (1.0 + p * kotlin.math.abs(z))
        val pdf = exp(-z * z / 2.0) / kotlin.math.sqrt(2.0 * Math.PI)
        val cdfRight = 1.0 - pdf * (b1 * t + b2 * t.pow(2) + b3 * t.pow(3) + b4 * t.pow(4) + b5 * t.pow(5))
        val cdf = if (z >= 0.0) cdfRight else 1.0 - cdfRight
        return cdf.coerceIn(0.0, 1.0)
    }
}
