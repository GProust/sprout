import XCTest
@testable import SproutData

/// The controls a running feed offers outside the app — the Live Activity, the
/// notification — are the timer's own (BDR-24). These are the cases that make a
/// tap there the same act as the same tap on the timer screen.
///
/// The same cases as `NursingControlsTest.kt`.
final class NursingControlsTests: XCTestCase {

    private let start: Int64 = 1_750_000_000_000
    private func min(_ n: Int64) -> Int64 { n * 60_000 }

    private var nursing: NursingSession {
        NursingSession(sessionStart: start, currentSide: .LEFT, segmentStart: start, babyUid: "baby-lea")
    }

    func testAFeedAtTheBreastOffersPauseSwitchAndStop() {
        XCTAssertEqual(nursingControls(for: nursing), [.PAUSE, .SWITCH, .STOP])
    }

    /// Coming back names the breast.
    func testAFeedOnABreakOffersEachBreastAndStop() {
        XCTAssertEqual(
            nursingControls(for: nursing.paused(at: start + min(8))),
            [.RESUME_LEFT, .RESUME_RIGHT, .STOP]
        )
    }

    func testEachControlIsTheTimersOwnAct() {
        let at = start + min(8)
        let paused = nursing.paused(at: at)

        XCTAssertEqual(nursing.after(.PAUSE, at: at), nursing.paused(at: at))
        XCTAssertEqual(nursing.after(.SWITCH, at: at), nursing.switched(at: at))
        XCTAssertEqual(paused.after(.RESUME_LEFT, at: at + min(2)), paused.resumed(on: .LEFT, at: at + min(2)))
        XCTAssertEqual(paused.after(.RESUME_RIGHT, at: at + min(2)), paused.resumed(on: .RIGHT, at: at + min(2)))
    }

    /// A double tap on Pause is a no-op the second time: the break's clock is
    /// not restarted.
    func testPausingTwiceKeepsTheFirstBreak() {
        let once = nursing.after(.PAUSE, at: start + min(8))

        let twice = once.after(.PAUSE, at: start + min(9))

        XCTAssertEqual(twice, once)
        XCTAssertEqual(twice.pausedMs(at: start + min(11)), min(3))
    }

    /// A stale Switch, tapped once the feed went on a break, does not resume it.
    func testAControlThatNoLongerFitsChangesNothing() {
        let paused = nursing.paused(at: start + min(8))

        XCTAssertEqual(paused.after(.SWITCH, at: start + min(9)), paused)
        XCTAssertEqual(nursing.after(.RESUME_RIGHT, at: start + min(9)), nursing)
        XCTAssertEqual(nursing.after(.STOP, at: start + min(9)), nursing)
    }

    /// Stop & save twice saves one feed: the second is handed nothing.
    func testStoppingTwiceSavesOnce() {
        let first = taking([nursing], "baby-lea")
        let second = taking(first.sessions, "baby-lea")

        XCTAssertEqual(first.taken, nursing)
        XCTAssertNil(second.taken)
    }

    /// The feed saved after taps outside the app is the feed the same taps on
    /// the timer save, and it ends when its last stretch ended — not when Stop
    /// was tapped.
    func testTheSavedFeedEndsWithItsLastStretch() {
        var sessions = [nursing]
        sessions = updating(sessions, "baby-lea") { $0.after(.SWITCH, at: self.start + self.min(6)) }
        sessions = updating(sessions, "baby-lea") { $0.after(.PAUSE, at: self.start + self.min(10)) }
        let taken = taking(sessions, "baby-lea").taken

        let feed = taken?.toFeeding(at: start + min(40))

        XCTAssertEqual(feed?.side, .BOTH)
        XCTAssertEqual(feed?.endTime, start + min(10))
        XCTAssertEqual(feed?.leftDurationMs, min(6))
        XCTAssertEqual(feed?.rightDurationMs, min(4))
    }

    func testTheSwitchIsOffUntilTurnedOn() {
        let store = InMemoryStore()

        XCTAssertFalse(NursingNotificationSettings.isEnabled(store))
        NursingNotificationSettings.setEnabled(true, in: store)
        XCTAssertTrue(NursingNotificationSettings.isEnabled(store))
    }
}
