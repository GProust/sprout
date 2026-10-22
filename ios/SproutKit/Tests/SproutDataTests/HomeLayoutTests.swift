import XCTest
@testable import SproutData

/// How the dashboard's log grid is arranged from what a parent stored (BDR-25).
///
/// The same cases as `HomeLayoutTest.kt`. An arrangement never leaves the phone
/// it was made on, but the rule is one product's: a log added in a later release
/// has to land in the same place on both apps, and a hidden one has to stay
/// hidden on both.
final class HomeLayoutTests: XCTestCase {

    private let feeding = "feeding"
    private let pumping = "pumping"
    private let sleep = "sleep"
    private let diaper = "diaper"
    private let growth = "growth"
    private let treatments = "treatments"
    private let medicines = "medicines"
    private let foods = "foods"
    private let wellbeing = "wellbeing"

    private var withoutWellbeing: [String] { homeTileIds.filter { $0 != wellbeing } }

    /// Stored, so pinned: renaming one would lose where every parent had put it.
    func testTheIdsAreTheOnesStored() {
        XCTAssertEqual(
            homeTileIds,
            ["feeding", "pumping", "sleep", "diaper", "growth", "treatments", "medicines", "foods", "wellbeing"]
        )
    }

    /// A parent who never opens *Customise home* sees the grid they always had,
    /// and no More.
    func testNothingStoredIsTheDefaultGrid() {
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: [], hidden: [], tracksWellbeing: true),
            HomeTiles(shown: homeTileIds, hidden: [])
        )
    }

    func testAStoredOrderIsFollowed() {
        let stored = [sleep, feeding, diaper, pumping, growth, treatments, medicines, foods, wellbeing]
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: stored, hidden: [], tracksWellbeing: true).shown,
            stored
        )
    }

    /// First foods arriving on a phone whose order was stored before it existed.
    func testANewTileAppearsShownAtItsDefaultPlace() {
        let beforeFoods = [feeding, pumping, sleep, diaper, growth, treatments, medicines, wellbeing]
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: beforeFoods, hidden: [], tracksWellbeing: true),
            HomeTiles(shown: homeTileIds, hidden: [])
        )

        // Its place in the default order, counted from the start, whatever the
        // parent has done with the others — and shown, whatever they hid.
        let rearranged = [medicines, feeding, pumping, sleep, diaper, growth, treatments, wellbeing]
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: rearranged, hidden: [pumping], tracksWellbeing: true),
            HomeTiles(
                shown: [medicines, feeding, sleep, diaper, growth, treatments, foods, wellbeing],
                hidden: [pumping]
            )
        )
    }

    func testANewTileAtTheEndOfTheDefaultsGoesAtTheEnd() {
        let defaults = homeTileIds + ["bath"]
        XCTAssertEqual(
            arrangeTiles(
                defaults: defaults,
                storedOrder: Array(homeTileIds.reversed()),
                hidden: [],
                tracksWellbeing: true
            ).shown,
            Array(homeTileIds.reversed()) + ["bath"]
        )
    }

    /// Written by a version that had a tile this one does not, or written twice.
    func testAnUnknownOrRepeatedIdIsIgnored() {
        let stored = [sleep, "nursery-notes", feeding, sleep]
        XCTAssertEqual(
            arrangeTiles(
                defaults: homeTileIds,
                storedOrder: stored,
                hidden: ["nursery-notes", pumping],
                tracksWellbeing: true
            ),
            HomeTiles(
                shown: [sleep, feeding, diaper, growth, treatments, medicines, foods, wellbeing],
                hidden: [pumping]
            )
        )
    }

    /// Off is off everywhere: not in the grid, not behind More, not in the
    /// editor (BDR-0006).
    func testWellbeingOffLeavesTheGridMoreAndTheEditor() {
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: [], hidden: [], tracksWellbeing: false),
            HomeTiles(shown: withoutWellbeing, hidden: [])
        )
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: [], hidden: [pumping, wellbeing], tracksWellbeing: false),
            HomeTiles(shown: withoutWellbeing.filter { $0 != pumping }, hidden: [pumping])
        )
        XCTAssertEqual(
            listedTiles(defaults: homeTileIds, storedOrder: [], tracksWellbeing: false),
            withoutWellbeing
        )
    }

    /// Turned back on, it is where the parent had put it, shown or hidden as it
    /// was.
    func testWellbeingComesBackWhereItWas() {
        let stored = [feeding, wellbeing, pumping, sleep, diaper, growth, treatments, medicines, foods]

        // Rearranged while it was off: First foods to the top of the editor.
        let moved = moveTile(defaults: homeTileIds, storedOrder: stored, id: foods, by: -7, tracksWellbeing: false)
        XCTAssertEqual(
            listedTiles(defaults: homeTileIds, storedOrder: moved, tracksWellbeing: false),
            [foods, feeding, pumping, sleep, diaper, growth, treatments, medicines]
        )

        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: moved, hidden: [], tracksWellbeing: true),
            HomeTiles(
                shown: [foods, wellbeing, feeding, pumping, sleep, diaper, growth, treatments, medicines],
                hidden: []
            )
        )
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: moved, hidden: [wellbeing], tracksWellbeing: true),
            HomeTiles(
                shown: [foods, feeding, pumping, sleep, diaper, growth, treatments, medicines],
                hidden: [wellbeing]
            )
        )
    }

    /// The grid is then the More tile alone, and every screen is still one tap
    /// away.
    func testEverythingHiddenLeavesEverythingBehindMore() {
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: [], hidden: Set(homeTileIds), tracksWellbeing: true),
            HomeTiles(shown: [], hidden: homeTileIds)
        )
    }

    /// Hidden is not forgotten: shown again, a tile is back in the place it held.
    func testAHiddenTileKeepsItsPlace() {
        let stored = [sleep, pumping, feeding, diaper, growth, treatments, medicines, foods, wellbeing]
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: stored, hidden: [pumping], tracksWellbeing: true).shown,
            stored.filter { $0 != pumping }
        )
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: stored, hidden: [], tracksWellbeing: true).shown,
            stored
        )
    }

    func testMoveUpAndMoveDownSwapNeighbours() {
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: pumping, by: -1, tracksWellbeing: true),
            [pumping, feeding, sleep, diaper, growth, treatments, medicines, foods, wellbeing]
        )
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: pumping, by: 1, tracksWellbeing: true),
            [feeding, sleep, pumping, diaper, growth, treatments, medicines, foods, wellbeing]
        )
    }

    /// A hidden tile is listed in the editor like any other, and moves like one.
    func testAHiddenTileCanBeMoved() {
        let moved = moveTile(defaults: homeTileIds, storedOrder: [], id: foods, by: -7, tracksWellbeing: true)
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: moved, hidden: [foods], tracksWellbeing: true),
            HomeTiles(
                shown: [feeding, pumping, sleep, diaper, growth, treatments, medicines, wellbeing],
                hidden: [foods]
            )
        )
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: moved, hidden: [], tracksWellbeing: true).shown.first,
            foods
        )
    }

    func testAMovePastEitherEndStopsThere() {
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: feeding, by: -1, tracksWellbeing: true),
            homeTileIds
        )
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: wellbeing, by: 1, tracksWellbeing: true),
            homeTileIds
        )
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: foods, by: 20, tracksWellbeing: true),
            [feeding, pumping, sleep, diaper, growth, treatments, medicines, wellbeing, foods]
        )
    }

    /// Neither wellbeing while it is off nor an id the app does not know can be
    /// moved.
    func testATileTheEditorDoesNotListDoesNotMove() {
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: wellbeing, by: -1, tracksWellbeing: false),
            homeTileIds
        )
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: "nursery-notes", by: -1, tracksWellbeing: true),
            homeTileIds
        )
        // And the last tile the editor lists, which is not the last stored.
        XCTAssertEqual(
            moveTile(defaults: homeTileIds, storedOrder: [], id: foods, by: 1, tracksWellbeing: false),
            homeTileIds
        )
    }

    /// The drag in *Customise home*: the editor's whole list in a new order.
    func testARearrangedListIsStoredInItsNewOrder() {
        let listed = [sleep, feeding, pumping, diaper, growth, treatments, medicines, foods]
        XCTAssertEqual(
            reorderTiles(defaults: homeTileIds, storedOrder: [], listed: listed),
            [sleep, feeding, pumping, diaper, growth, treatments, medicines, foods, wellbeing]
        )
    }

    /// Anything but a rearrangement of what the editor showed changes nothing.
    func testAListThatIsNotARearrangementChangesNothing() {
        let stored = [sleep, feeding, pumping, diaper, growth, treatments, medicines, foods, wellbeing]
        XCTAssertEqual(reorderTiles(defaults: homeTileIds, storedOrder: stored, listed: [feeding, feeding]), stored)
        XCTAssertEqual(reorderTiles(defaults: homeTileIds, storedOrder: stored, listed: ["nursery-notes", feeding]), stored)
    }

    /// *Restore the default order* forgets the order, and only the order.
    func testRestoringTheDefaultOrderKeepsWhatIsHidden() {
        XCTAssertEqual(
            arrangeTiles(defaults: homeTileIds, storedOrder: [], hidden: [treatments, pumping], tracksWellbeing: true),
            HomeTiles(
                shown: [feeding, sleep, diaper, growth, medicines, foods, wellbeing],
                hidden: [pumping, treatments]
            )
        )
    }

    // MARK: - The store

    func testNothingStoredReadsAsTheDefault() {
        XCTAssertEqual(HomeLayoutSettings(store: InMemoryStore()).arrangement, HomeArrangement())
    }

    func testTheStoreKeepsTheOrderAndWhatIsHidden() {
        let store = InMemoryStore()
        let settings = HomeLayoutSettings(store: store)
        let order = moveTile(defaults: homeTileIds, storedOrder: [], id: sleep, by: -2, tracksWellbeing: true)

        settings.setOrder(order)
        settings.setShown(pumping, false)
        settings.setShown(treatments, false)
        settings.setShown(treatments, true)

        // Read back through a second value over the same store, as the next
        // launch would.
        XCTAssertEqual(
            HomeLayoutSettings(store: store).arrangement,
            HomeArrangement(order: order, hidden: [pumping])
        )
    }

    func testRestoringTheDefaultOrderStoresNothingAndKeepsWhatIsHidden() {
        let store = InMemoryStore()
        let settings = HomeLayoutSettings(store: store)
        settings.setOrder(Array(homeTileIds.reversed()))
        settings.setShown(pumping, false)

        settings.restoreDefaultOrder()

        XCTAssertEqual(settings.arrangement, HomeArrangement(order: [], hidden: [pumping]))
        XCTAssertNil(store.string(forKey: "home_tile_order"))
    }
}
