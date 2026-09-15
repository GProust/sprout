import Foundation

/// The WHO Child Growth Standards, and enough arithmetic to answer the one
/// question a parent actually asks in front of a growth chart: *is this normal?*
///
/// Ported from `ui/stats/WhoGrowth.kt`.
///
/// ## Where the numbers come from
///
/// The WHO publishes each standard as three parameters per month of age — the
/// Box-Cox power `l`, the median `m` and the coefficient of variation `s` — from
/// which every percentile of the reference population can be recomputed exactly.
/// Storing the LMS triples rather than a handful of pre-computed percentile
/// columns is what lets Sprout say "the 42nd percentile" instead of only
/// "somewhere between the 15th and the 85th".
///
/// The tables are the WHO Child Growth Standards (0–5 years, 2006) for
/// weight-for-age, length-for-age and head-circumference-for-age, girls and
/// boys. They are the *standards* — how healthy, breastfed children under
/// recommended conditions grow — not a survey of how children in any one country
/// happen to grow.
///
/// ## Why only the first two years
///
/// Sprout is a newborn and postpartum tracker, and length-for-age changes
/// measurement at 24 months (lying down becomes standing up, with its own
/// table). Rather than quietly compare a toddler's height against a length
/// reference, the curves stop at ``whoMaxAgeMonths`` and the screen says so.
///
/// ## Why both sexes, always
///
/// The standards are separate for girls and boys, and **Sprout deliberately does
/// not ask a baby's sex** (BDR-0008) — nothing else in the app needs it, and a
/// tracker that asks for data it has no use for is a tracker that has to be
/// trusted with it. So a measurement is read against *both* references and
/// reported as the pair; "inside the band" means inside for at least one of them,
/// which is the honest answer when we don't know which one applies.
///
/// None of this is a diagnosis. A single point outside the band is common and
/// usually means nothing; what a clinician reads is the shape of the line over
/// time. The screen is worded accordingly.

/// Which of the two published references — the tables are separate.
public enum WhoSex: String, CaseIterable, Sendable { case girls, boys }

/// A measurement with a WHO standard behind it, in the unit that standard is
/// published in: ``weight`` in kilograms, ``length`` and ``head`` in centimetres.
/// Callers convert from the record's grams and millimetres.
public enum GrowthMeasure: String, CaseIterable, Sendable { case weight, length, head }

/// The last age the tables here cover; past it there is no band to draw.
public let whoMaxAgeMonths: Double = 24.0

/// The WHO month, which is a twelfth of a year rather than a calendar one.
private let daysPerMonth = 30.4375

/// Age in WHO months at `at`, as a fraction — the tables interpolate between
/// whole months.
public func ageInMonths(birthDateMillis: Int64, at: Int64) -> Double {
    Double(at - birthDateMillis) / (daysPerMonth * 24 * 60 * 60 * 1000)
}

/// The z-scores of the percentile lines Sprout draws. These are the five the WHO
/// growth charts themselves are printed with, so a parent comparing Sprout to the
/// chart in their health record is looking at the same lines.
public let zP3: Double = -1.880794
public let zP15: Double = -1.036433
public let zP50: Double = 0.0
public let zP85: Double = 1.036433
public let zP97: Double = 1.880794

/// One month of a WHO table: the Box-Cox power, the median, and the coefficient
/// of variation.
private struct Lms {
    let l: Double
    let m: Double
    let s: Double
}

/// The reference value at `zScore` for a `sex` of `ageMonths`, in the measure's
/// own unit; `nil` past the end of the tables.
///
/// This is the LMS distribution read forwards: `M(1 + LSz)^(1/L)`, or its
/// lognormal limit when `L` is zero.
public func whoValueAt(
    measure: GrowthMeasure,
    sex: WhoSex,
    ageMonths: Double,
    zScore: Double
) -> Double? {
    guard let lms = lmsAt(measure: measure, sex: sex, ageMonths: ageMonths) else { return nil }
    if lms.l == 0 { return lms.m * exp(lms.s * zScore) }
    let base = 1 + lms.l * lms.s * zScore
    // Negative bases have no real root; far outside anything we draw, but a NaN
    // silently plotted at the top of a chart is worse than a gap.
    guard base > 0 else { return nil }
    return lms.m * pow(base, 1 / lms.l)
}

/// Where `value` sits in the reference distribution, as a z-score; `nil` past the
/// end of the tables or for a value that cannot be measured (zero or less).
///
/// The LMS distribution read backwards, the inverse of ``whoValueAt``.
public func whoZScore(
    measure: GrowthMeasure,
    sex: WhoSex,
    ageMonths: Double,
    value: Double
) -> Double? {
    guard value > 0 else { return nil }
    guard let lms = lmsAt(measure: measure, sex: sex, ageMonths: ageMonths) else { return nil }
    if lms.l == 0 { return log(value / lms.m) / lms.s }
    return (pow(value / lms.m, lms.l) - 1) / (lms.l * lms.s)
}

/// The percentile (0–100) a z-score corresponds to — the standard normal CDF.
///
/// Zelen & Severo's rational approximation (Abramowitz & Stegun 26.2.17), whose
/// error stays under 7.5e-8: far tighter than a percentile shown to a whole
/// number needs, and it keeps this file free of any dependency.
public func percentileOf(_ zScore: Double) -> Double {
    let t = 1.0 / (1.0 + 0.2316419 * abs(zScore))
    let poly = t * (
        0.319381530 + t * (
            -0.356563782 + t * (
                1.781477937 + t * (-1.821255978 + t * 1.330274429)
            )
        )
    )
    let tail = exp(-zScore * zScore / 2.0) / (2.0 * Double.pi).squareRoot() * poly
    return 100.0 * (zScore >= 0 ? 1.0 - tail : tail)
}

/// One reference's five percentile lines at a given age.
public struct WhoPercentiles: Equatable, Sendable {
    public let p3: Double
    public let p15: Double
    public let p50: Double
    public let p85: Double
    public let p97: Double
}

/// One age's worth of the reference, carrying both published standards in full.
///
/// They are deliberately *not* merged into a single band here. The screen shows
/// either one of them or both side by side, and flattening the pair into an
/// envelope — or worse, an average of two curves that describe different
/// populations — would throw away the thing the parent is being shown.
public struct WhoBandPoint: Equatable, Sendable {
    public let ageMonths: Double
    public let girls: WhoPercentiles
    public let boys: WhoPercentiles

    /// The reference for one sex, so a caller can loop over what it is drawing.
    public func of(_ sex: WhoSex) -> WhoPercentiles { sex == .girls ? girls : boys }
}

/// The band for `measure` from `fromMonths` to `toMonths`, sampled at `steps` + 1
/// evenly spaced ages. Ages past the tables are dropped, so a request that runs
/// off the end returns the part that exists.
public func whoBand(
    measure: GrowthMeasure,
    fromMonths: Double = 0,
    toMonths: Double = whoMaxAgeMonths,
    steps: Int = 48
) -> [WhoBandPoint] {
    guard steps > 0, toMonths >= fromMonths else { return [] }
    let span = toMonths - fromMonths
    return (0...steps).compactMap { i in
        let age = fromMonths + span * Double(i) / Double(steps)
        guard let girls = percentilesAt(measure: measure, sex: .girls, ageMonths: age),
              let boys = percentilesAt(measure: measure, sex: .boys, ageMonths: age)
        else { return nil }
        return WhoBandPoint(ageMonths: age, girls: girls, boys: boys)
    }
}

private func percentilesAt(
    measure: GrowthMeasure,
    sex: WhoSex,
    ageMonths: Double
) -> WhoPercentiles? {
    func at(_ z: Double) -> Double? {
        whoValueAt(measure: measure, sex: sex, ageMonths: ageMonths, zScore: z)
    }
    guard let p3 = at(zP3), let p15 = at(zP15), let p50 = at(zP50),
          let p85 = at(zP85), let p97 = at(zP97)
    else { return nil }
    return WhoPercentiles(p3: p3, p15: p15, p50: p50, p85: p85, p97: p97)
}

/// Where one measurement lands, read against one reference or both.
///
/// With ``girlsPercentile`` and ``boysPercentile`` equal, the reader chose a
/// reference and gets a single number; otherwise the pair is the span, and
/// ``insideBand`` is the lenient reading — the baby is one sex or the other, so a
/// value inside P3–P97 for *either* reference is inside the band for a baby it
/// could belong to. Saying otherwise would flag healthy babies on the strength of
/// a fact Sprout chose not to ask for.
public struct WhoPlacement: Equatable, Sendable {
    public let girlsPercentile: Double
    public let boysPercentile: Double

    /// The narrower and wider readings, whichever reference each came from.
    public var lowPercentile: Double { min(girlsPercentile, boysPercentile) }
    public var highPercentile: Double { max(girlsPercentile, boysPercentile) }

    /// True while the value is between the 3rd and 97th percentile of at least
    /// one reference.
    public var insideBand: Bool { highPercentile >= 3.0 && lowPercentile <= 97.0 }
}

/// `value` read at `ageMonths`; `nil` past the tables.
///
/// `only` restricts the reading to one reference — the reader picked a curve, so
/// both halves of the pair come back the same and the screen shows one number.
public func whoPlacement(
    measure: GrowthMeasure,
    ageMonths: Double,
    value: Double,
    only: WhoSex? = nil
) -> WhoPlacement? {
    guard let girls = whoZScore(
        measure: measure, sex: only ?? .girls, ageMonths: ageMonths, value: value
    ),
    let boys = whoZScore(
        measure: measure, sex: only ?? .boys, ageMonths: ageMonths, value: value
    )
    else { return nil }
    return WhoPlacement(girlsPercentile: percentileOf(girls), boysPercentile: percentileOf(boys))
}

/// The LMS triple for a fractional age, linearly interpolated between the two
/// whole months either side of it — the usual way these monthly tables are read
/// for a baby who is, as babies are, not a whole number of months old.
private func lmsAt(measure: GrowthMeasure, sex: WhoSex, ageMonths: Double) -> Lms? {
    let table = tableFor(measure: measure, sex: sex)
    let lastIndex = table.count - 1
    guard ageMonths >= 0, ageMonths <= Double(lastIndex) else { return nil }
    let lower = Int(ageMonths.rounded(.down))
    if lower >= lastIndex { return table[lastIndex] }
    let fraction = ageMonths - Double(lower)
    let a = table[lower]
    let b = table[lower + 1]
    return Lms(
        l: a.l + (b.l - a.l) * fraction,
        m: a.m + (b.m - a.m) * fraction,
        s: a.s + (b.s - a.s) * fraction
    )
}

private func tableFor(measure: GrowthMeasure, sex: WhoSex) -> [Lms] {
    switch measure {
    case .weight: return sex == .girls ? weightGirls : weightBoys
    case .length: return sex == .girls ? lengthGirls : lengthBoys
    case .head: return sex == .girls ? headGirls : headBoys
    }
}

// MARK: - The tables
//
// One row per completed month, 0 through 24. Weight in kilograms; length and
// head circumference in centimetres.
//
// **Reference data, not code.** These are the published WHO LMS parameters, and
// `WhoGrowthTests` checks the curves they produce against the WHO's own printed
// z-score values. Never "tidy" a number here — if a table has to change, it is
// re-derived from the published tables, not edited.
//
// They were transcribed from `ui/stats/WhoGrowth.kt` by a script rather than by
// hand, for the obvious reason: 150 rows of six-figure decimals retyped by
// anyone, human or otherwise, is 150 chances to change what the app tells a
// parent about their baby's weight.

private let weightGirls: [Lms] = [
    Lms(l: 0.3809, m: 3.2322, s: 0.14171),//  0 mo
    Lms(l: 0.1714, m: 4.1873, s: 0.13724),//  1 mo
    Lms(l: 0.0962, m: 5.1282, s: 0.13), //  2 mo
    Lms(l: 0.0402, m: 5.8458, s: 0.12619),//  3 mo
    Lms(l: -0.005, m: 6.4237, s: 0.12402),//  4 mo
    Lms(l: -0.043, m: 6.8985, s: 0.12274),//  5 mo
    Lms(l: -0.0756, m: 7.297, s: 0.12204),//  6 mo
    Lms(l: -0.1039, m: 7.6422, s: 0.12178),//  7 mo
    Lms(l: -0.1288, m: 7.9487, s: 0.12181),//  8 mo
    Lms(l: -0.1507, m: 8.2254, s: 0.12199),//  9 mo
    Lms(l: -0.17, m: 8.48, s: 0.12223), // 10 mo
    Lms(l: -0.1872, m: 8.7192, s: 0.12247),// 11 mo
    Lms(l: -0.2024, m: 8.9481, s: 0.12268),// 12 mo
    Lms(l: -0.2158, m: 9.1699, s: 0.12283),// 13 mo
    Lms(l: -0.2278, m: 9.387, s: 0.12294),// 14 mo
    Lms(l: -0.2384, m: 9.6008, s: 0.12299),// 15 mo
    Lms(l: -0.2478, m: 9.8124, s: 0.12303),// 16 mo
    Lms(l: -0.2562, m: 10.0226, s: 0.12306),// 17 mo
    Lms(l: -0.2637, m: 10.2315, s: 0.12309),// 18 mo
    Lms(l: -0.2703, m: 10.4393, s: 0.12315),// 19 mo
    Lms(l: -0.2762, m: 10.6464, s: 0.12323),// 20 mo
    Lms(l: -0.2815, m: 10.8534, s: 0.12335),// 21 mo
    Lms(l: -0.2862, m: 11.0608, s: 0.1235),// 22 mo
    Lms(l: -0.2903, m: 11.2688, s: 0.12369),// 23 mo
    Lms(l: -0.2941, m: 11.4775, s: 0.1239),// 24 mo
]

private let weightBoys: [Lms] = [
    Lms(l: 0.3487, m: 3.3464, s: 0.14602),//  0 mo
    Lms(l: 0.2297, m: 4.4709, s: 0.13395),//  1 mo
    Lms(l: 0.197, m: 5.5675, s: 0.12385),//  2 mo
    Lms(l: 0.1738, m: 6.3762, s: 0.11727),//  3 mo
    Lms(l: 0.1553, m: 7.0023, s: 0.11316),//  4 mo
    Lms(l: 0.1395, m: 7.5105, s: 0.1108),//  5 mo
    Lms(l: 0.1257, m: 7.934, s: 0.10958),//  6 mo
    Lms(l: 0.1134, m: 8.297, s: 0.10902),//  7 mo
    Lms(l: 0.1021, m: 8.6151, s: 0.10882),//  8 mo
    Lms(l: 0.0917, m: 8.9014, s: 0.10881),//  9 mo
    Lms(l: 0.082, m: 9.1649, s: 0.10891),// 10 mo
    Lms(l: 0.073, m: 9.4122, s: 0.10906),// 11 mo
    Lms(l: 0.0644, m: 9.6479, s: 0.10925),// 12 mo
    Lms(l: 0.0563, m: 9.8749, s: 0.10949),// 13 mo
    Lms(l: 0.0487, m: 10.0953, s: 0.10976),// 14 mo
    Lms(l: 0.0413, m: 10.3108, s: 0.11007),// 15 mo
    Lms(l: 0.0343, m: 10.5228, s: 0.11041),// 16 mo
    Lms(l: 0.0275, m: 10.7319, s: 0.11079),// 17 mo
    Lms(l: 0.0211, m: 10.9385, s: 0.11119),// 18 mo
    Lms(l: 0.0148, m: 11.143, s: 0.11164),// 19 mo
    Lms(l: 0.0087, m: 11.3462, s: 0.11211),// 20 mo
    Lms(l: 0.0029, m: 11.5486, s: 0.11261),// 21 mo
    Lms(l: -0.0028, m: 11.7504, s: 0.11314),// 22 mo
    Lms(l: -0.0083, m: 11.9514, s: 0.11369),// 23 mo
    Lms(l: -0.0137, m: 12.1515, s: 0.11426),// 24 mo
]

private let lengthGirls: [Lms] = [
    Lms(l: 1.0, m: 49.1477, s: 0.0379), //  0 mo
    Lms(l: 1.0, m: 53.6872, s: 0.0364), //  1 mo
    Lms(l: 1.0, m: 57.0673, s: 0.03568),//  2 mo
    Lms(l: 1.0, m: 59.8029, s: 0.0352), //  3 mo
    Lms(l: 1.0, m: 62.0899, s: 0.03486),//  4 mo
    Lms(l: 1.0, m: 64.0301, s: 0.03463),//  5 mo
    Lms(l: 1.0, m: 65.7311, s: 0.03448),//  6 mo
    Lms(l: 1.0, m: 67.2873, s: 0.03441),//  7 mo
    Lms(l: 1.0, m: 68.7498, s: 0.0344), //  8 mo
    Lms(l: 1.0, m: 70.1435, s: 0.03444),//  9 mo
    Lms(l: 1.0, m: 71.4818, s: 0.03452),// 10 mo
    Lms(l: 1.0, m: 72.771, s: 0.03464), // 11 mo
    Lms(l: 1.0, m: 74.015, s: 0.03479), // 12 mo
    Lms(l: 1.0, m: 75.2176, s: 0.03496),// 13 mo
    Lms(l: 1.0, m: 76.3817, s: 0.03514),// 14 mo
    Lms(l: 1.0, m: 77.5099, s: 0.03534),// 15 mo
    Lms(l: 1.0, m: 78.6055, s: 0.03555),// 16 mo
    Lms(l: 1.0, m: 79.671, s: 0.03576), // 17 mo
    Lms(l: 1.0, m: 80.7079, s: 0.03598),// 18 mo
    Lms(l: 1.0, m: 81.7182, s: 0.0362), // 19 mo
    Lms(l: 1.0, m: 82.7036, s: 0.03643),// 20 mo
    Lms(l: 1.0, m: 83.6654, s: 0.03666),// 21 mo
    Lms(l: 1.0, m: 84.604, s: 0.03688), // 22 mo
    Lms(l: 1.0, m: 85.5202, s: 0.03711),// 23 mo
    Lms(l: 1.0, m: 86.4153, s: 0.03734),// 24 mo
]

private let lengthBoys: [Lms] = [
    Lms(l: 1.0, m: 49.8842, s: 0.03795),//  0 mo
    Lms(l: 1.0, m: 54.7244, s: 0.03557),//  1 mo
    Lms(l: 1.0, m: 58.4249, s: 0.03424),//  2 mo
    Lms(l: 1.0, m: 61.4292, s: 0.03328),//  3 mo
    Lms(l: 1.0, m: 63.886, s: 0.03257), //  4 mo
    Lms(l: 1.0, m: 65.9026, s: 0.03204),//  5 mo
    Lms(l: 1.0, m: 67.6236, s: 0.03165),//  6 mo
    Lms(l: 1.0, m: 69.1645, s: 0.03139),//  7 mo
    Lms(l: 1.0, m: 70.5994, s: 0.03124),//  8 mo
    Lms(l: 1.0, m: 71.9687, s: 0.03117),//  9 mo
    Lms(l: 1.0, m: 73.2812, s: 0.03118),// 10 mo
    Lms(l: 1.0, m: 74.5388, s: 0.03125),// 11 mo
    Lms(l: 1.0, m: 75.7488, s: 0.03137),// 12 mo
    Lms(l: 1.0, m: 76.9186, s: 0.03154),// 13 mo
    Lms(l: 1.0, m: 78.0497, s: 0.03174),// 14 mo
    Lms(l: 1.0, m: 79.1458, s: 0.03197),// 15 mo
    Lms(l: 1.0, m: 80.2113, s: 0.03222),// 16 mo
    Lms(l: 1.0, m: 81.2487, s: 0.0325), // 17 mo
    Lms(l: 1.0, m: 82.2587, s: 0.03279),// 18 mo
    Lms(l: 1.0, m: 83.2418, s: 0.0331), // 19 mo
    Lms(l: 1.0, m: 84.1996, s: 0.03342),// 20 mo
    Lms(l: 1.0, m: 85.1348, s: 0.03376),// 21 mo
    Lms(l: 1.0, m: 86.0477, s: 0.0341), // 22 mo
    Lms(l: 1.0, m: 86.941, s: 0.03445), // 23 mo
    Lms(l: 1.0, m: 87.8161, s: 0.03479),// 24 mo
]

private let headGirls: [Lms] = [
    Lms(l: 1.0, m: 33.8787, s: 0.03496),//  0 mo
    Lms(l: 1.0, m: 36.5463, s: 0.03210),//  1 mo
    Lms(l: 1.0, m: 38.2521, s: 0.03168),//  2 mo
    Lms(l: 1.0, m: 39.5328, s: 0.03140),//  3 mo
    Lms(l: 1.0, m: 40.5817, s: 0.03119),//  4 mo
    Lms(l: 1.0, m: 41.4590, s: 0.03102),//  5 mo
    Lms(l: 1.0, m: 42.1995, s: 0.03087),//  6 mo
    Lms(l: 1.0, m: 42.8290, s: 0.03075),//  7 mo
    Lms(l: 1.0, m: 43.3671, s: 0.03063),//  8 mo
    Lms(l: 1.0, m: 43.8300, s: 0.03053),//  9 mo
    Lms(l: 1.0, m: 44.2319, s: 0.03044),// 10 mo
    Lms(l: 1.0, m: 44.5844, s: 0.03035),// 11 mo
    Lms(l: 1.0, m: 44.8965, s: 0.03027),// 12 mo
    Lms(l: 1.0, m: 45.1752, s: 0.03019),// 13 mo
    Lms(l: 1.0, m: 45.4265, s: 0.03012),// 14 mo
    Lms(l: 1.0, m: 45.6551, s: 0.03006),// 15 mo
    Lms(l: 1.0, m: 45.8650, s: 0.02999),// 16 mo
    Lms(l: 1.0, m: 46.0598, s: 0.02993),// 17 mo
    Lms(l: 1.0, m: 46.2424, s: 0.02987),// 18 mo
    Lms(l: 1.0, m: 46.4152, s: 0.02982),// 19 mo
    Lms(l: 1.0, m: 46.5801, s: 0.02977),// 20 mo
    Lms(l: 1.0, m: 46.7384, s: 0.02972),// 21 mo
    Lms(l: 1.0, m: 46.8913, s: 0.02967),// 22 mo
    Lms(l: 1.0, m: 47.0391, s: 0.02962),// 23 mo
    Lms(l: 1.0, m: 47.1822, s: 0.02957),// 24 mo
]

private let headBoys: [Lms] = [
    Lms(l: 1.0, m: 34.4618, s: 0.03686),//  0 mo
    Lms(l: 1.0, m: 37.2759, s: 0.03133),//  1 mo
    Lms(l: 1.0, m: 39.1285, s: 0.02997),//  2 mo
    Lms(l: 1.0, m: 40.5135, s: 0.02918),//  3 mo
    Lms(l: 1.0, m: 41.6317, s: 0.02868),//  4 mo
    Lms(l: 1.0, m: 42.5576, s: 0.02837),//  5 mo
    Lms(l: 1.0, m: 43.3306, s: 0.02817),//  6 mo
    Lms(l: 1.0, m: 43.9803, s: 0.02804),//  7 mo
    Lms(l: 1.0, m: 44.5300, s: 0.02796),//  8 mo
    Lms(l: 1.0, m: 44.9998, s: 0.02792),//  9 mo
    Lms(l: 1.0, m: 45.4051, s: 0.02790),// 10 mo
    Lms(l: 1.0, m: 45.7573, s: 0.02789),// 11 mo
    Lms(l: 1.0, m: 46.0661, s: 0.02789),// 12 mo
    Lms(l: 1.0, m: 46.3395, s: 0.02789),// 13 mo
    Lms(l: 1.0, m: 46.5844, s: 0.02791),// 14 mo
    Lms(l: 1.0, m: 46.8060, s: 0.02792),// 15 mo
    Lms(l: 1.0, m: 47.0088, s: 0.02795),// 16 mo
    Lms(l: 1.0, m: 47.1962, s: 0.02797),// 17 mo
    Lms(l: 1.0, m: 47.3711, s: 0.02800),// 18 mo
    Lms(l: 1.0, m: 47.5357, s: 0.02803),// 19 mo
    Lms(l: 1.0, m: 47.6919, s: 0.02806),// 20 mo
    Lms(l: 1.0, m: 47.8408, s: 0.02810),// 21 mo
    Lms(l: 1.0, m: 47.9833, s: 0.02813),// 22 mo
    Lms(l: 1.0, m: 48.1201, s: 0.02817),// 23 mo
    Lms(l: 1.0, m: 48.2515, s: 0.02821),// 24 mo
]
