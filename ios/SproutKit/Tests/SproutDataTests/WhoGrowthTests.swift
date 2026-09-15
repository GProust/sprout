import XCTest
@testable import SproutData

/// The WHO tables are reference data a parent may act on, so these tests check
/// them against values **published by the WHO itself** rather than against
/// whatever the code happens to produce: the medians and the ±2 SD figures below
/// are printed in the standards' own z-score tables.
///
/// That is the whole point of having them twice. A test that compared this port
/// to the Kotlin it came from would pass just as happily on a mistyped digit.
final class WhoGrowthTests: XCTestCase {

    /// Rounded the way the published tables round, so the two are comparable.
    private func oneDecimal(_ value: Double) -> Double { (value * 10).rounded() / 10 }

    func testMediansMatchThePublishedTables() throws {
        // Weight-for-age medians, in kg.
        XCTAssertEqual(try median(.weight, .boys, 0), 3.3464, accuracy: 1e-4)
        XCTAssertEqual(try median(.weight, .girls, 0), 3.2322, accuracy: 1e-4)
        XCTAssertEqual(try median(.weight, .boys, 12), 9.6479, accuracy: 1e-4)
        XCTAssertEqual(try median(.weight, .girls, 12), 8.9481, accuracy: 1e-4)

        // Length-for-age and head-circumference-for-age medians, in cm.
        XCTAssertEqual(try median(.length, .boys, 0), 49.8842, accuracy: 1e-4)
        XCTAssertEqual(try median(.length, .girls, 0), 49.1477, accuracy: 1e-4)
        XCTAssertEqual(try median(.head, .boys, 0), 34.4618, accuracy: 1e-4)
        XCTAssertEqual(try median(.head, .girls, 0), 33.8787, accuracy: 1e-4)
    }

    func testStandardDeviationsMatchThePublishedTables() throws {
        // Birth weight: the familiar 2.5 kg / 4.4 kg boundaries for a boy.
        XCTAssertEqual(oneDecimal(try value(.weight, .boys, 0, -2)), 2.5)
        XCTAssertEqual(oneDecimal(try value(.weight, .boys, 0, 2)), 4.4)
        XCTAssertEqual(oneDecimal(try value(.weight, .girls, 0, -2)), 2.4)
        XCTAssertEqual(oneDecimal(try value(.weight, .girls, 0, 2)), 4.2)

        // And two more, further along each table.
        XCTAssertEqual(oneDecimal(try value(.weight, .boys, 6, 2)), 9.8)
        XCTAssertEqual(oneDecimal(try value(.length, .girls, 0, -3)), 43.6)
    }

    func testZScoreIsTheInverseOfTheValue() throws {
        for measure in GrowthMeasure.allCases {
            for sex in WhoSex.allCases {
                for age in [0.0, 0.7, 5.0, 11.9, 24.0] {
                    for z in [-2.5, -1.0, 0.0, 0.5, 2.5] {
                        let v = try value(measure, sex, age, z)
                        let back = try XCTUnwrap(
                            whoZScore(measure: measure, sex: sex, ageMonths: age, value: v)
                        )
                        XCTAssertEqual(back, z, accuracy: 1e-9, "\(measure) \(sex) \(age) \(z)")
                    }
                }
            }
        }
    }

    func testPercentilesMatchTheirZScores() {
        XCTAssertEqual(percentileOf(zP3), 3.0, accuracy: 0.01)
        XCTAssertEqual(percentileOf(zP15), 15.0, accuracy: 0.01)
        XCTAssertEqual(percentileOf(zP50), 50.0, accuracy: 0.01)
        XCTAssertEqual(percentileOf(zP85), 85.0, accuracy: 0.01)
        XCTAssertEqual(percentileOf(zP97), 97.0, accuracy: 0.01)

        // The tails stay inside the scale rather than wrapping round it.
        XCTAssertTrue((0.0...0.001).contains(percentileOf(-6)))
        XCTAssertTrue((99.999...100.0).contains(percentileOf(6)))
    }

    func testAgeBetweenWholeMonthsIsInterpolated() throws {
        let oneMonth = try median(.weight, .boys, 1)
        let twoMonths = try median(.weight, .boys, 2)
        let halfway = try median(.weight, .boys, 1.5)

        XCTAssertEqual(halfway, 5.0192, accuracy: 1e-3)
        XCTAssertTrue(halfway > oneMonth && halfway < twoMonths)
    }

    func testPastTheTablesThereIsNoAnswer() {
        XCTAssertNil(whoValueAt(
            measure: .weight, sex: .boys, ageMonths: whoMaxAgeMonths + 0.1, zScore: zP50
        ))
        XCTAssertNil(whoValueAt(measure: .weight, sex: .boys, ageMonths: -0.1, zScore: zP50))
        XCTAssertNil(whoPlacement(measure: .weight, ageMonths: 30, value: 12))

        // The last month itself is covered.
        XCTAssertNotNil(whoValueAt(
            measure: .weight, sex: .boys, ageMonths: whoMaxAgeMonths, zScore: zP50
        ))
    }

    func testAnUnmeasurableValueHasNoPlacement() {
        XCTAssertNil(whoZScore(measure: .weight, sex: .boys, ageMonths: 3, value: 0))
        XCTAssertNil(whoPlacement(measure: .weight, ageMonths: 3, value: -1))
    }

    /// A boy's median weight at 3 months, read as if the sex were unknown: the
    /// 50th percentile for boys, higher for girls, whose median is lower.
    func testPlacementReadsBothReferences() throws {
        let placement = try XCTUnwrap(whoPlacement(measure: .weight, ageMonths: 3, value: 6.3762))

        XCTAssertEqual(placement.boysPercentile, 50.0, accuracy: 0.05)
        XCTAssertTrue(placement.girlsPercentile > 50.0)
        XCTAssertEqual(placement.lowPercentile, placement.boysPercentile)
        XCTAssertEqual(placement.highPercentile, placement.girlsPercentile)
        XCTAssertTrue(placement.insideBand)
    }

    /// The reader picked a curve, so there is no span left over.
    func testPlacementAgainstOneReferenceIsASingleNumber() throws {
        let girlsMedian = try median(.weight, .girls, 6)
        let chosen = try XCTUnwrap(
            whoPlacement(measure: .weight, ageMonths: 6, value: girlsMedian, only: .girls)
        )

        XCTAssertEqual(chosen.girlsPercentile, 50.0, accuracy: 0.05)
        XCTAssertEqual(chosen.lowPercentile, chosen.highPercentile)

        // The same measurement read against both still opens back up.
        let both = try XCTUnwrap(whoPlacement(measure: .weight, ageMonths: 6, value: girlsMedian))
        XCTAssertTrue(both.highPercentile - both.lowPercentile > 5.0)
        XCTAssertEqual(both.girlsPercentile, 50.0, accuracy: 0.05)
        XCTAssertTrue(both.boysPercentile < 50.0)
    }

    /// Saying otherwise would flag healthy babies on the strength of a fact
    /// Sprout chose not to ask for.
    func testInsideTheBandIsTrueWhenEitherReferenceWouldSaySo() throws {
        // Just inside the girls' 97th at 6 months is well past the boys' — and
        // still "inside", because the baby may well be a girl.
        let girls97 = try value(.weight, .girls, 6, zP97)
        let justUnder = try XCTUnwrap(
            whoPlacement(measure: .weight, ageMonths: 6, value: girls97 - 0.01)
        )
        XCTAssertTrue(justUnder.insideBand)

        // Far above both references, it is not.
        let farAbove = try XCTUnwrap(
            whoPlacement(measure: .weight, ageMonths: 6, value: girls97 + 3.0)
        )
        XCTAssertFalse(farAbove.insideBand)
        // And far below both.
        let farBelow = try XCTUnwrap(whoPlacement(measure: .weight, ageMonths: 6, value: 3.0))
        XCTAssertFalse(farBelow.insideBand)
    }

    func testBandKeepsBothReferencesApartAndEachOneIsOrdered() {
        let band = whoBand(measure: .weight, fromMonths: 0, toMonths: whoMaxAgeMonths, steps: 24)

        XCTAssertEqual(band.count, 25)
        XCTAssertEqual(band.first?.ageMonths ?? -1, 0, accuracy: 1e-9)
        XCTAssertEqual(band.last?.ageMonths ?? -1, whoMaxAgeMonths, accuracy: 1e-9)

        for point in band {
            for sex in WhoSex.allCases {
                let p = point.of(sex)
                XCTAssertTrue(p.p3 < p.p15)
                XCTAssertTrue(p.p15 < p.p50)
                XCTAssertTrue(p.p50 < p.p85)
                XCTAssertTrue(p.p85 < p.p97)
            }
            // The two references are distinct, which is why they are drawn
            // separately rather than merged: boys are the heavier standard
            // throughout the first two years.
            XCTAssertTrue(point.boys.p50 > point.girls.p50)
        }

        // And each curve rises.
        for (a, b) in zip(band, band.dropFirst()) {
            XCTAssertTrue(b.boys.p50 > a.boys.p50)
            XCTAssertTrue(b.girls.p50 > a.girls.p50)
        }
    }

    func testBandAgreesWithTheValueItIsBuiltFrom() throws {
        for point in whoBand(measure: .head, fromMonths: 0, toMonths: 6, steps: 6) {
            XCTAssertEqual(
                point.girls.p50,
                try value(.head, .girls, point.ageMonths, zP50),
                accuracy: 1e-9
            )
            XCTAssertEqual(
                point.boys.p97,
                try value(.head, .boys, point.ageMonths, zP97),
                accuracy: 1e-9
            )
        }
    }

    func testBandStopsWhereTheTablesDo() {
        XCTAssertTrue(
            whoBand(measure: .weight, fromMonths: 20, toMonths: 40, steps: 20)
                .allSatisfy { $0.ageMonths <= whoMaxAgeMonths }
        )
        XCTAssertTrue(whoBand(measure: .weight, fromMonths: 30, toMonths: 40, steps: 10).isEmpty)
        XCTAssertTrue(whoBand(measure: .weight, fromMonths: 10, toMonths: 5, steps: 10).isEmpty)
        XCTAssertTrue(whoBand(measure: .weight, steps: 0).isEmpty)
    }

    func testAgeInMonthsCountsWhoMonths() {
        let day: Double = 24 * 60 * 60 * 1000

        XCTAssertEqual(ageInMonths(birthDateMillis: 0, at: 0), 0, accuracy: 1e-9)
        // The WHO month is a twelfth of a year: 30.4375 days. Which is why a
        // 365-day-old baby is a shade under twelve months, not exactly twelve.
        XCTAssertEqual(
            ageInMonths(birthDateMillis: 0, at: Int64(30.4375 * day)), 1, accuracy: 1e-6
        )
        XCTAssertEqual(
            ageInMonths(birthDateMillis: 0, at: Int64(365.25 * day)), 12, accuracy: 1e-6
        )
        XCTAssertEqual(
            ageInMonths(birthDateMillis: 0, at: Int64(365 * day)), 11.9918, accuracy: 1e-4
        )
    }

    // MARK: - Helpers

    private func value(
        _ measure: GrowthMeasure, _ sex: WhoSex, _ age: Double, _ z: Double
    ) throws -> Double {
        try XCTUnwrap(whoValueAt(measure: measure, sex: sex, ageMonths: age, zScore: z))
    }

    private func median(_ measure: GrowthMeasure, _ sex: WhoSex, _ age: Double) throws -> Double {
        try value(measure, sex, age, zP50)
    }
}
