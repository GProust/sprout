import XCTest
@testable import SproutData

/// Who sees the "what's new" card, and with what in it (BDR-22).
///
/// The same cases as `WhatsNewTest.kt`, because the two phones in one household
/// should not disagree about whether there is news.
final class WhatsNewTests: XCTestCase {

    /// A phone updating from before announcements existed: the newest only, not
    /// the history.
    func testNothingStoredOffersTheNewestOnly() {
        XCTAssertEqual(announcementsToShow(available: [1, 2, 3], lastSeen: nil), [3])
    }

    func testTheOnesNotYetSeenAreOfferedNewestFirst() {
        XCTAssertEqual(announcementsToShow(available: [1, 2, 3], lastSeen: 1), [3, 2])
    }

    /// Read, put away, or a fresh install that finished onboarding.
    func testEverythingSeenOffersNothing() {
        XCTAssertEqual(announcementsToShow(available: [1, 2, 3], lastSeen: 3), [])
    }

    /// A backup from a newer version restored onto this one.
    func testAPhoneThatHasSeenMoreThanThisVersionCarriesOffersNothing() {
        XCTAssertEqual(announcementsToShow(available: [1, 2], lastSeen: 5), [])
    }

    func testNoAnnouncementsOffersNothing() {
        XCTAssertEqual(announcementsToShow(available: [], lastSeen: nil), [])
    }

    /// Ids only ever grow: a reused or out-of-order one would read as already seen.
    func testIdsAreDistinctAndAppendedInOrder() {
        XCTAssertEqual(Array(Set(announcementIds)).sorted(), announcementIds)
    }

    func testMarkingSeenLeavesNothingToShow() {
        let settings = WhatsNewSettings(store: InMemoryStore())
        XCTAssertNil(settings.lastSeen)

        settings.markAllSeen()

        XCTAssertEqual(settings.lastSeen, announcementIds.max())
        XCTAssertEqual(announcementsToShow(available: announcementIds, lastSeen: settings.lastSeen), [])
    }
}
