package com.gproust.sprout.ui.stats

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The WHO Child Growth Standards, and enough arithmetic to answer the one
 * question a parent actually asks in front of a growth chart: *is this normal?*
 *
 * ## Where the numbers come from
 *
 * The WHO publishes each standard as three parameters per month of age — the
 * Box-Cox power [Lms.l], the median [Lms.m] and the coefficient of variation
 * [Lms.s] — from which every percentile of the reference population can be
 * recomputed exactly. Storing the LMS triples rather than a handful of
 * pre-computed percentile columns is what lets Sprout say "the 42nd percentile"
 * instead of only "somewhere between the 15th and the 85th".
 *
 * The tables below are the WHO Child Growth Standards (0–5 years, 2006) for
 * weight-for-age, length-for-age and head-circumference-for-age, girls and
 * boys, transcribed from the published z-score tables. They are the *standards*
 * — how healthy, breastfed children under recommended conditions grow — not a
 * survey of how children in any one country happen to grow.
 *
 * ## Why only the first two years
 *
 * Sprout is a newborn and postpartum tracker, and length-for-age changes
 * measurement at 24 months (lying down becomes standing up, with its own
 * table). Rather than quietly compare a toddler's height against a length
 * reference, the curves simply stop at [WHO_MAX_AGE_MONTHS] and the screen
 * says so.
 *
 * ## Why both sexes, always
 *
 * The standards are separate for girls and boys, and Sprout deliberately does
 * not ask a baby's sex — nothing else in the app needs it, and a tracker that
 * asks for data it has no use for is a tracker that has to be trusted with it.
 * So a measurement is read against *both* references and reported as the pair
 * ([WhoPlacement]); "inside the band" means inside for at least one of them,
 * which is the honest answer when we don't know which one applies.
 *
 * None of this is a diagnosis. A single point outside the band is common and
 * usually means nothing; what a clinician reads is the shape of the line over
 * time. The screen is worded accordingly.
 */

/** Which of the two published references — the tables are separate. */
enum class WhoSex { GIRLS, BOYS }

/**
 * A measurement with a WHO standard behind it, in the unit that standard is
 * published in: [WEIGHT] in kilograms, [LENGTH] and [HEAD] in centimetres.
 * Callers convert from the entity's grams and millimetres.
 */
enum class GrowthMeasure { WEIGHT, LENGTH, HEAD }

/** The last age the tables here cover; past it there is no band to draw. */
const val WHO_MAX_AGE_MONTHS: Double = 24.0

/** The WHO month, which is a twelfth of a year rather than a calendar one. */
private const val DAYS_PER_MONTH = 30.4375

/** Age in WHO months at [at], as a fraction — the tables interpolate between whole months. */
fun ageInMonths(birthDateMillis: Long, at: Long): Double =
    (at - birthDateMillis).toDouble() / (DAYS_PER_MONTH * 24 * 60 * 60 * 1000)

/**
 * The z-scores of the percentile lines Sprout draws. These are the five the WHO
 * growth charts themselves are printed with, so a parent comparing Sprout to
 * the chart in their health record is looking at the same lines.
 */
const val Z_P3: Double = -1.880794
const val Z_P15: Double = -1.036433
const val Z_P50: Double = 0.0
const val Z_P85: Double = 1.036433
const val Z_P97: Double = 1.880794

/** One month of a WHO table: the Box-Cox power, the median, and the coefficient of variation. */
private data class Lms(val l: Double, val m: Double, val s: Double)

/**
 * The reference value at [zScore] for a [sex] of [ageMonths], in the measure's
 * own unit; null past the end of the tables.
 *
 * This is the LMS distribution read forwards: `M(1 + LSz)^(1/L)`, or its
 * lognormal limit when `L` is zero.
 */
fun whoValueAt(
    measure: GrowthMeasure,
    sex: WhoSex,
    ageMonths: Double,
    zScore: Double,
): Double? {
    val lms = lmsAt(measure, sex, ageMonths) ?: return null
    if (lms.l == 0.0) return lms.m * exp(lms.s * zScore)
    val base = 1 + lms.l * lms.s * zScore
    // Negative bases have no real root; far outside anything we draw, but a
    // NaN silently plotted at the top of a chart is worse than a gap.
    if (base <= 0) return null
    return lms.m * base.pow(1 / lms.l)
}

/**
 * Where [value] sits in the reference distribution, as a z-score; null past the
 * end of the tables or for a value that cannot be measured (zero or less).
 *
 * The LMS distribution read backwards, the inverse of [whoValueAt].
 */
fun whoZScore(
    measure: GrowthMeasure,
    sex: WhoSex,
    ageMonths: Double,
    value: Double,
): Double? {
    if (value <= 0) return null
    val lms = lmsAt(measure, sex, ageMonths) ?: return null
    return if (lms.l == 0.0) {
        ln(value / lms.m) / lms.s
    } else {
        ((value / lms.m).pow(lms.l) - 1) / (lms.l * lms.s)
    }
}

/**
 * The percentile (0–100) a z-score corresponds to — the standard normal CDF.
 *
 * Zelen & Severo's rational approximation (Abramowitz & Stegun 26.2.17), whose
 * error stays under 7.5e-8: far tighter than a percentile shown to a whole
 * number needs, and it keeps this file free of any dependency.
 */
fun percentileOf(zScore: Double): Double {
    val t = 1.0 / (1.0 + 0.2316419 * abs(zScore))
    val poly = t * (
        0.319381530 + t * (
            -0.356563782 + t * (
                1.781477937 + t * (-1.821255978 + t * 1.330274429)
                )
            )
        )
    val tail = exp(-zScore * zScore / 2.0) / sqrt(2.0 * PI) * poly
    return 100.0 * if (zScore >= 0) 1.0 - tail else tail
}

/**
 * One age's worth of the band drawn behind a baby's own measurements.
 *
 * Since the sex is unknown, each edge takes whichever reference is further out
 * — the band is the union of the girls' and the boys' — while the two medians
 * are kept apart, because their gap is exactly the uncertainty being admitted
 * to.
 */
data class WhoBandPoint(
    val ageMonths: Double,
    val p3: Double,
    val p15: Double,
    val p85: Double,
    val p97: Double,
    val girlsMedian: Double,
    val boysMedian: Double,
)

/**
 * The band for [measure] from [fromMonths] to [toMonths], sampled at [steps]+1
 * evenly spaced ages. Ages past the tables are dropped, so a request that runs
 * off the end returns the part that exists.
 */
fun whoBand(
    measure: GrowthMeasure,
    fromMonths: Double = 0.0,
    toMonths: Double = WHO_MAX_AGE_MONTHS,
    steps: Int = 48,
): List<WhoBandPoint> {
    if (steps <= 0 || toMonths < fromMonths) return emptyList()
    val span = toMonths - fromMonths
    return (0..steps).mapNotNull { i ->
        val age = fromMonths + span * i / steps
        fun at(sex: WhoSex, z: Double) = whoValueAt(measure, sex, age, z)
        val girlsMedian = at(WhoSex.GIRLS, Z_P50) ?: return@mapNotNull null
        val boysMedian = at(WhoSex.BOYS, Z_P50) ?: return@mapNotNull null
        WhoBandPoint(
            ageMonths = age,
            p3 = minOf(at(WhoSex.GIRLS, Z_P3) ?: return@mapNotNull null, at(WhoSex.BOYS, Z_P3) ?: return@mapNotNull null),
            p15 = minOf(at(WhoSex.GIRLS, Z_P15) ?: return@mapNotNull null, at(WhoSex.BOYS, Z_P15) ?: return@mapNotNull null),
            p85 = maxOf(at(WhoSex.GIRLS, Z_P85) ?: return@mapNotNull null, at(WhoSex.BOYS, Z_P85) ?: return@mapNotNull null),
            p97 = maxOf(at(WhoSex.GIRLS, Z_P97) ?: return@mapNotNull null, at(WhoSex.BOYS, Z_P97) ?: return@mapNotNull null),
            girlsMedian = girlsMedian,
            boysMedian = boysMedian,
        )
    }
}

/**
 * Where one measurement lands, read against both references.
 *
 * [insideBand] is the lenient reading, and deliberately so: the baby is one sex
 * or the other, so a value inside P3–P97 for *either* reference is inside the
 * band for a baby it could belong to. Saying otherwise would flag healthy
 * babies on the strength of a fact Sprout chose not to ask for.
 */
data class WhoPlacement(
    val girlsPercentile: Double,
    val boysPercentile: Double,
) {
    /** The narrower and wider readings, whichever reference each came from. */
    val lowPercentile: Double get() = minOf(girlsPercentile, boysPercentile)
    val highPercentile: Double get() = maxOf(girlsPercentile, boysPercentile)

    /** True while the value is between the 3rd and 97th percentile of at least one reference. */
    val insideBand: Boolean get() = highPercentile >= 3.0 && lowPercentile <= 97.0
}

/** [value] read against both references at [ageMonths]; null past the tables. */
fun whoPlacement(measure: GrowthMeasure, ageMonths: Double, value: Double): WhoPlacement? {
    val girls = whoZScore(measure, WhoSex.GIRLS, ageMonths, value) ?: return null
    val boys = whoZScore(measure, WhoSex.BOYS, ageMonths, value) ?: return null
    return WhoPlacement(percentileOf(girls), percentileOf(boys))
}

/**
 * The LMS triple for a fractional age, linearly interpolated between the two
 * whole months either side of it — the usual way these monthly tables are read
 * for a baby who is, as babies are, not a whole number of months old.
 */
private fun lmsAt(measure: GrowthMeasure, sex: WhoSex, ageMonths: Double): Lms? {
    val table = tableFor(measure, sex)
    if (ageMonths < 0 || ageMonths > table.lastIndex) return null
    val lower = floor(ageMonths).toInt()
    if (lower >= table.lastIndex) return table.last()
    val fraction = ageMonths - lower
    val a = table[lower]
    val b = table[lower + 1]
    return Lms(
        l = a.l + (b.l - a.l) * fraction,
        m = a.m + (b.m - a.m) * fraction,
        s = a.s + (b.s - a.s) * fraction,
    )
}

private fun tableFor(measure: GrowthMeasure, sex: WhoSex): List<Lms> = when (measure) {
    GrowthMeasure.WEIGHT -> if (sex == WhoSex.GIRLS) WEIGHT_GIRLS else WEIGHT_BOYS
    GrowthMeasure.LENGTH -> if (sex == WhoSex.GIRLS) LENGTH_GIRLS else LENGTH_BOYS
    GrowthMeasure.HEAD -> if (sex == WhoSex.GIRLS) HEAD_GIRLS else HEAD_BOYS
}

// --- The tables. One row per completed month, 0 through 24.
// Weight in kilograms; length and head circumference in centimetres.

private val WEIGHT_GIRLS = listOf(
    Lms(0.3809, 3.2322, 0.14171),         //  0 mo
    Lms(0.1714, 4.1873, 0.13724),         //  1 mo
    Lms(0.0962, 5.1282, 0.13),            //  2 mo
    Lms(0.0402, 5.8458, 0.12619),         //  3 mo
    Lms(-0.005, 6.4237, 0.12402),         //  4 mo
    Lms(-0.043, 6.8985, 0.12274),         //  5 mo
    Lms(-0.0756, 7.297, 0.12204),         //  6 mo
    Lms(-0.1039, 7.6422, 0.12178),        //  7 mo
    Lms(-0.1288, 7.9487, 0.12181),        //  8 mo
    Lms(-0.1507, 8.2254, 0.12199),        //  9 mo
    Lms(-0.17, 8.48, 0.12223),            // 10 mo
    Lms(-0.1872, 8.7192, 0.12247),        // 11 mo
    Lms(-0.2024, 8.9481, 0.12268),        // 12 mo
    Lms(-0.2158, 9.1699, 0.12283),        // 13 mo
    Lms(-0.2278, 9.387, 0.12294),         // 14 mo
    Lms(-0.2384, 9.6008, 0.12299),        // 15 mo
    Lms(-0.2478, 9.8124, 0.12303),        // 16 mo
    Lms(-0.2562, 10.0226, 0.12306),       // 17 mo
    Lms(-0.2637, 10.2315, 0.12309),       // 18 mo
    Lms(-0.2703, 10.4393, 0.12315),       // 19 mo
    Lms(-0.2762, 10.6464, 0.12323),       // 20 mo
    Lms(-0.2815, 10.8534, 0.12335),       // 21 mo
    Lms(-0.2862, 11.0608, 0.1235),        // 22 mo
    Lms(-0.2903, 11.2688, 0.12369),       // 23 mo
    Lms(-0.2941, 11.4775, 0.1239),        // 24 mo
)

private val WEIGHT_BOYS = listOf(
    Lms(0.3487, 3.3464, 0.14602),         //  0 mo
    Lms(0.2297, 4.4709, 0.13395),         //  1 mo
    Lms(0.197, 5.5675, 0.12385),          //  2 mo
    Lms(0.1738, 6.3762, 0.11727),         //  3 mo
    Lms(0.1553, 7.0023, 0.11316),         //  4 mo
    Lms(0.1395, 7.5105, 0.1108),          //  5 mo
    Lms(0.1257, 7.934, 0.10958),          //  6 mo
    Lms(0.1134, 8.297, 0.10902),          //  7 mo
    Lms(0.1021, 8.6151, 0.10882),         //  8 mo
    Lms(0.0917, 8.9014, 0.10881),         //  9 mo
    Lms(0.082, 9.1649, 0.10891),          // 10 mo
    Lms(0.073, 9.4122, 0.10906),          // 11 mo
    Lms(0.0644, 9.6479, 0.10925),         // 12 mo
    Lms(0.0563, 9.8749, 0.10949),         // 13 mo
    Lms(0.0487, 10.0953, 0.10976),        // 14 mo
    Lms(0.0413, 10.3108, 0.11007),        // 15 mo
    Lms(0.0343, 10.5228, 0.11041),        // 16 mo
    Lms(0.0275, 10.7319, 0.11079),        // 17 mo
    Lms(0.0211, 10.9385, 0.11119),        // 18 mo
    Lms(0.0148, 11.143, 0.11164),         // 19 mo
    Lms(0.0087, 11.3462, 0.11211),        // 20 mo
    Lms(0.0029, 11.5486, 0.11261),        // 21 mo
    Lms(-0.0028, 11.7504, 0.11314),       // 22 mo
    Lms(-0.0083, 11.9514, 0.11369),       // 23 mo
    Lms(-0.0137, 12.1515, 0.11426),       // 24 mo
)

private val LENGTH_GIRLS = listOf(
    Lms(1.0, 49.1477, 0.0379),            //  0 mo
    Lms(1.0, 53.6872, 0.0364),            //  1 mo
    Lms(1.0, 57.0673, 0.03568),           //  2 mo
    Lms(1.0, 59.8029, 0.0352),            //  3 mo
    Lms(1.0, 62.0899, 0.03486),           //  4 mo
    Lms(1.0, 64.0301, 0.03463),           //  5 mo
    Lms(1.0, 65.7311, 0.03448),           //  6 mo
    Lms(1.0, 67.2873, 0.03441),           //  7 mo
    Lms(1.0, 68.7498, 0.0344),            //  8 mo
    Lms(1.0, 70.1435, 0.03444),           //  9 mo
    Lms(1.0, 71.4818, 0.03452),           // 10 mo
    Lms(1.0, 72.771, 0.03464),            // 11 mo
    Lms(1.0, 74.015, 0.03479),            // 12 mo
    Lms(1.0, 75.2176, 0.03496),           // 13 mo
    Lms(1.0, 76.3817, 0.03514),           // 14 mo
    Lms(1.0, 77.5099, 0.03534),           // 15 mo
    Lms(1.0, 78.6055, 0.03555),           // 16 mo
    Lms(1.0, 79.671, 0.03576),            // 17 mo
    Lms(1.0, 80.7079, 0.03598),           // 18 mo
    Lms(1.0, 81.7182, 0.0362),            // 19 mo
    Lms(1.0, 82.7036, 0.03643),           // 20 mo
    Lms(1.0, 83.6654, 0.03666),           // 21 mo
    Lms(1.0, 84.604, 0.03688),            // 22 mo
    Lms(1.0, 85.5202, 0.03711),           // 23 mo
    Lms(1.0, 86.4153, 0.03734),           // 24 mo
)

private val LENGTH_BOYS = listOf(
    Lms(1.0, 49.8842, 0.03795),           //  0 mo
    Lms(1.0, 54.7244, 0.03557),           //  1 mo
    Lms(1.0, 58.4249, 0.03424),           //  2 mo
    Lms(1.0, 61.4292, 0.03328),           //  3 mo
    Lms(1.0, 63.886, 0.03257),            //  4 mo
    Lms(1.0, 65.9026, 0.03204),           //  5 mo
    Lms(1.0, 67.6236, 0.03165),           //  6 mo
    Lms(1.0, 69.1645, 0.03139),           //  7 mo
    Lms(1.0, 70.5994, 0.03124),           //  8 mo
    Lms(1.0, 71.9687, 0.03117),           //  9 mo
    Lms(1.0, 73.2812, 0.03118),           // 10 mo
    Lms(1.0, 74.5388, 0.03125),           // 11 mo
    Lms(1.0, 75.7488, 0.03137),           // 12 mo
    Lms(1.0, 76.9186, 0.03154),           // 13 mo
    Lms(1.0, 78.0497, 0.03174),           // 14 mo
    Lms(1.0, 79.1458, 0.03197),           // 15 mo
    Lms(1.0, 80.2113, 0.03222),           // 16 mo
    Lms(1.0, 81.2487, 0.0325),            // 17 mo
    Lms(1.0, 82.2587, 0.03279),           // 18 mo
    Lms(1.0, 83.2418, 0.0331),            // 19 mo
    Lms(1.0, 84.1996, 0.03342),           // 20 mo
    Lms(1.0, 85.1348, 0.03376),           // 21 mo
    Lms(1.0, 86.0477, 0.0341),            // 22 mo
    Lms(1.0, 86.941, 0.03445),            // 23 mo
    Lms(1.0, 87.8161, 0.03479),           // 24 mo
)

private val HEAD_GIRLS = listOf(
    Lms(1.0, 33.8787, 0.03496),           //  0 mo
    Lms(1.0, 36.5463, 0.03210),           //  1 mo
    Lms(1.0, 38.2521, 0.03168),           //  2 mo
    Lms(1.0, 39.5328, 0.03140),           //  3 mo
    Lms(1.0, 40.5817, 0.03119),           //  4 mo
    Lms(1.0, 41.4590, 0.03102),           //  5 mo
    Lms(1.0, 42.1995, 0.03087),           //  6 mo
    Lms(1.0, 42.8290, 0.03075),           //  7 mo
    Lms(1.0, 43.3671, 0.03063),           //  8 mo
    Lms(1.0, 43.8300, 0.03053),           //  9 mo
    Lms(1.0, 44.2319, 0.03044),           // 10 mo
    Lms(1.0, 44.5844, 0.03035),           // 11 mo
    Lms(1.0, 44.8965, 0.03027),           // 12 mo
    Lms(1.0, 45.1752, 0.03019),           // 13 mo
    Lms(1.0, 45.4265, 0.03012),           // 14 mo
    Lms(1.0, 45.6551, 0.03006),           // 15 mo
    Lms(1.0, 45.8650, 0.02999),           // 16 mo
    Lms(1.0, 46.0598, 0.02993),           // 17 mo
    Lms(1.0, 46.2424, 0.02987),           // 18 mo
    Lms(1.0, 46.4152, 0.02982),           // 19 mo
    Lms(1.0, 46.5801, 0.02977),           // 20 mo
    Lms(1.0, 46.7384, 0.02972),           // 21 mo
    Lms(1.0, 46.8913, 0.02967),           // 22 mo
    Lms(1.0, 47.0391, 0.02962),           // 23 mo
    Lms(1.0, 47.1822, 0.02957),           // 24 mo
)

private val HEAD_BOYS = listOf(
    Lms(1.0, 34.4618, 0.03686),           //  0 mo
    Lms(1.0, 37.2759, 0.03133),           //  1 mo
    Lms(1.0, 39.1285, 0.02997),           //  2 mo
    Lms(1.0, 40.5135, 0.02918),           //  3 mo
    Lms(1.0, 41.6317, 0.02868),           //  4 mo
    Lms(1.0, 42.5576, 0.02837),           //  5 mo
    Lms(1.0, 43.3306, 0.02817),           //  6 mo
    Lms(1.0, 43.9803, 0.02804),           //  7 mo
    Lms(1.0, 44.5300, 0.02796),           //  8 mo
    Lms(1.0, 44.9998, 0.02792),           //  9 mo
    Lms(1.0, 45.4051, 0.02790),           // 10 mo
    Lms(1.0, 45.7573, 0.02789),           // 11 mo
    Lms(1.0, 46.0661, 0.02789),           // 12 mo
    Lms(1.0, 46.3395, 0.02789),           // 13 mo
    Lms(1.0, 46.5844, 0.02791),           // 14 mo
    Lms(1.0, 46.8060, 0.02792),           // 15 mo
    Lms(1.0, 47.0088, 0.02795),           // 16 mo
    Lms(1.0, 47.1962, 0.02797),           // 17 mo
    Lms(1.0, 47.3711, 0.02800),           // 18 mo
    Lms(1.0, 47.5357, 0.02803),           // 19 mo
    Lms(1.0, 47.6919, 0.02806),           // 20 mo
    Lms(1.0, 47.8408, 0.02810),           // 21 mo
    Lms(1.0, 47.9833, 0.02813),           // 22 mo
    Lms(1.0, 48.1201, 0.02817),           // 23 mo
    Lms(1.0, 48.2515, 0.02821),           // 24 mo
)
