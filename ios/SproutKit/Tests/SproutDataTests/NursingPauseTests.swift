import XCTest
@testable import SproutData

/// A feed can stop for a burp and carry on (BDR-17), and the whole feature is
/// one rule: **a break is not time at the breast**. Every clock in the app reads
/// the session through these functions, so this is where that rule is held.
///
/// The same cases as `NursingPauseTest.kt`, because a household with one phone
/// of each has to get the same answer to "how long did they feed for".
final class NursingPauseTests: XCTestCase {

    private let start: Int64 = 1_750_000_000_000
    private func min(_ n: Int64) -> Int64 { n * 60_000 }

    private var session: NursingSession {
        NursingSession(sessionStart: start, currentSide: .LEFT, segmentStart: start)
    }

    func testARunningSessionCountsTheBreastItIsOn() {
        XCTAssertFalse(session.isPaused)
        XCTAssertEqual(session.nursedMs(at: start + min(8)), min(8))
        XCTAssertEqual(session.nursedMs(on: .LEFT, at: start + min(8)), min(8))
        XCTAssertEqual(session.nursedMs(on: .RIGHT, at: start + min(8)), 0)
        XCTAssertEqual(session.pausedMs(at: start + min(8)), 0)
    }

    func testPausingBanksTheStretchAndStopsTheClock() {
        let paused = session.paused(at: start + min(8))

        XCTAssertTrue(paused.isPaused)
        XCTAssertEqual(
            paused.segments,
            [NursingSegment(side: .LEFT, startTime: start, endTime: start + min(8))]
        )
        // Five minutes of burping later, still eight minutes of feeding.
        XCTAssertEqual(paused.nursedMs(at: start + min(13)), min(8))
        XCTAssertEqual(paused.pausedMs(at: start + min(13)), min(5))
    }

    func testPausingAgainDoesNotRestartTheBreak() {
        let paused = session.paused(at: start + min(8))

        let again = paused.paused(at: start + min(11))

        XCTAssertEqual(again, paused)
        XCTAssertEqual(again.pausedMs(at: start + min(13)), min(5))
    }

    func testResumingOnTheOtherBreastLeavesTheBreakAsAGap() {
        let resumed = session.paused(at: start + min(8)).resumed(on: .RIGHT, at: start + min(13))

        XCTAssertFalse(resumed.isPaused)
        XCTAssertEqual(resumed.currentSide, .RIGHT)
        XCTAssertEqual(resumed.nursedMs(on: .LEFT, at: start + min(19)), min(8))
        XCTAssertEqual(resumed.nursedMs(on: .RIGHT, at: start + min(19)), min(6))
        XCTAssertEqual(resumed.nursedMs(at: start + min(19)), min(14))
        XCTAssertEqual(breastfeedPausedMillis(resumed.allSegments(endingAt: start + min(19))), min(5))
    }

    func testResumingOnTheSameBreastIsAllowed() {
        // The nappy halfway through the left side: the feed carries on where it
        // was, and the two stretches are still two stretches.
        let resumed = session.paused(at: start + min(4)).resumed(on: .LEFT, at: start + min(9))

        XCTAssertEqual(resumed.currentSide, .LEFT)
        XCTAssertEqual(resumed.allSegments(endingAt: start + min(12)).count, 2)
        XCTAssertEqual(resumed.nursedMs(on: .LEFT, at: start + min(12)), min(7))
    }

    func testSwitchingSidesLeavesNoBreakBehind() {
        let switched = session.switched(at: start + min(8))

        XCTAssertFalse(switched.isPaused)
        XCTAssertEqual(switched.currentSide, .RIGHT)
        XCTAssertEqual(breastfeedPausedMillis(switched.allSegments(endingAt: start + min(14))), 0)
        XCTAssertEqual(switched.nursedMs(at: start + min(14)), min(14))
    }

    func testAFeedThatRanStraightThroughHasNoBreaks() {
        let segments = [
            NursingSegment(side: .LEFT, startTime: start, endTime: start + min(8)),
            NursingSegment(side: .RIGHT, startTime: start + min(8), endTime: start + min(14)),
        ]

        XCTAssertEqual(breastfeedPausedMillis(segments), 0)
    }

    func testTheStatisticsCountTheBreastAndNotTheBurping() {
        // What the session above would be saved as: 14 minutes of feeding
        // across a 19-minute wall clock.
        let entry = Feeding(
            type: .BREAST,
            side: .BOTH,
            startTime: start,
            endTime: start + min(19),
            leftDurationMs: min(8),
            rightDurationMs: min(6),
            segments: [
                NursingSegment(side: .LEFT, startTime: start, endTime: start + min(8)),
                NursingSegment(side: .RIGHT, startTime: start + min(13), endTime: start + min(19)),
            ]
        )

        XCTAssertEqual(breastfeedMillis(entry), min(14))
        XCTAssertEqual(breastfeedPausedMillis(entry.nursingSegments), min(5))
    }
}
