import XCTest

/// Captures every screen, in every language Sprout speaks.
///
/// The iOS counterpart of `androidTest/.../ScreenshotTest.kt`, and it exists for
/// the same two reasons: the App Store listing needs them, and a pull request is
/// far easier to review when you can see what changed than when you can only
/// read what changed.
///
/// The app is launched with `-sprout-screenshots`, which gives it a fixed
/// in-memory database and a frozen clock (see `ScreenshotSeed`), so a capture is
/// reproducible: the same numbers in the same places in all seven languages,
/// which is what makes the set look like one app rather than seven.
final class ScreenshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testCaptureEveryScreen() throws {
        let language = ProcessInfo.processInfo.environment["SPROUT_LANGUAGE"] ?? "en"
        let app = XCUIApplication()
        app.launchArguments = [
            "-sprout-screenshots",
            // How you force a language on a running app without touching the
            // app's own code: the standard defaults both take a launch argument.
            "-AppleLanguages", "(\(language))",
            "-AppleLocale", language,
        ]
        app.launch()

        // The dashboard, then each log opened from its grid. The logs are
        // *pushed* from that grid rather than given a tab, which is the whole of
        // BDR-0010 — so a run that photographed only the tabs would miss every
        // screen the change moved.
        try capture(app, tab: 0, named: "01-home", language: language)
        for (index, log) in Self.logs.enumerated() {
            try captureLog(app, tile: log, named: String(format: "%02d-%@", index + 2, log), language: language)
        }

        try captureFeedingJoin(app, language: language)
        try captureMedicines(app, language: language)

        // Then the other tabs. Three in total with the seeded single baby: the
        // baby's own tab appears only from two children up, because with one the
        // dashboard already is that view.
        try capture(app, tab: 1, named: "09-trends", language: language)
        try capture(app, tab: 2, named: "10-you", language: language)

        // And the check-in, which is reached from the You tab rather than the
        // grid — the one screen a parent is *offered* rather than goes looking
        // for, so worth seeing as it is offered (BDR-0006).
        let checkIn = app.buttons.matching(identifier: "you-entry-checkin").firstMatch
        if checkIn.waitForExistence(timeout: 5) {
            checkIn.tap()
            try file(app, named: "11-checkin", language: language)
        }

        try captureSettingsAndSharing(app, language: language)
    }

    /// Settings, and the sharing screen behind it.
    ///
    /// Two taps deep from the dashboard rather than a tab, so it is the one pair
    /// of screens a capture would otherwise never reach — and sharing is the
    /// part of the app with the most to explain, which makes it the part most
    /// worth looking at.
    private func captureSettingsAndSharing(_ app: XCUIApplication, language: String) throws {
        // Back to the dashboard, but not filed again — "01-home" already is
        // that picture.
        app.tabBars.buttons.element(boundBy: 0).tap()

        let gear = app.buttons["home-settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 15), "no settings button on the dashboard")
        gear.tap()
        try file(app, named: "12-settings", language: language)

        // By `descendants` rather than `buttons`: a `NavigationLink` inside a
        // `Form` is a cell on some iOS versions and a button on others, and
        // which one it is today is not something worth pinning a run to.
        let sync = app.descendants(matching: .any).matching(identifier: "settings-sync").firstMatch
        XCTAssertTrue(
            scroll(app, to: sync),
            "no sharing row in settings, even after scrolling to the bottom"
        )
        sync.tap()
        try file(app, named: "13-sync", language: language)
    }

    /// The first run, which no seeded capture can reach.
    ///
    /// Seeding a profile is exactly what makes onboarding not appear, so this is
    /// a second launch with nothing in the database — the only way to photograph
    /// the four screens every parent actually starts on.
    func testCaptureFirstRun() throws {
        let language = ProcessInfo.processInfo.environment["SPROUT_LANGUAGE"] ?? "en"
        let app = XCUIApplication()
        app.launchArguments = [
            "-sprout-screenshots-empty",
            "-AppleLanguages", "(\(language))",
            "-AppleLocale", language,
        ]
        app.launch()

        // Onboarding is not inside the shell, so there is no navigation bar to
        // wait for — each step is anchored on its own button instead.
        let start = app.buttons["onboarding-start"]
        XCTAssertTrue(start.waitForExistence(timeout: 60), "the app did not open on onboarding")
        try file(app, named: "00-onboarding-1-welcome", language: language, waitingFor: start)

        // Through the three steps that ask something. Each is filed before it is
        // answered, so the captures show what a parent is asked rather than what
        // this test typed.
        let next = app.buttons["onboarding-next"]
        start.tap()
        try file(app, named: "00-onboarding-2-about-you", language: language, waitingFor: next)

        // The name is the one required answer, so it has to be given before
        // *Next* will move.
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "no name field on the About you step")
        field.tap()
        field.typeText("Alex")

        next.tap()
        try file(app, named: "00-onboarding-3-baby", language: language, waitingFor: next)

        next.tap()
        try file(
            app,
            named: "00-onboarding-4-care",
            language: language,
            waitingFor: app.buttons["onboarding-finish"]
        )
    }

    /// The grid's tiles, in the order they are drawn. Every one of them is a
    /// real screen now, which is the point of BDR-0010: treatments used to be
    /// the one that could not fit in the bar.
    private static let logs = [
        "feeding", "pumping", "sleep", "diaper", "growth", "treatments", "wellbeing",
    ]

    /// The as-needed medicines, and the form a new one starts in (BDR-15).
    ///
    /// Its own function rather than another entry in `logs`, which numbers its
    /// captures by position: adding to that list would renumber every file after
    /// it. These two share a number with the treatments capture instead, the way
    /// Android's sibling screens already do.
    ///
    /// Two captures because they answer different questions. The list is the one
    /// a parent opens at 3 a.m. — three medicines, one in each state the traffic
    /// light has. The editor is the one that is easiest to misread, so it is
    /// worth showing that the prefilled six-to-eight hours sits under a line
    /// saying the numbers come from a prescriber or the leaflet.
    private func captureMedicines(_ app: XCUIApplication, language: String) throws {
        let tile = app.buttons["log-tile-medicines"]
        XCTAssertTrue(
            scroll(app, to: tile),
            "07-medicines: no medicines tile on the dashboard, even after scrolling"
        )
        tile.tap()
        try file(app, named: "07-medicines", language: language)

        let add = app.buttons["medicine-add"]
        XCTAssertTrue(
            add.waitForExistence(timeout: 10),
            "07-medicines-2-new: no add button on the medicines screen"
        )
        add.tap()
        try file(app, named: "07-medicines-2-new", language: language)

        // Out of the sheet and back to the dashboard, so the captures that
        // follow start where they expect to. By identifier and not by position:
        // the sheet puts a second navigation bar on screen, and which one
        // `firstMatch` picks is not something to bet a seven-language run on.
        let cancel = app.buttons["medicine-editor-cancel"]
        XCTAssertTrue(
            cancel.waitForExistence(timeout: 10),
            "07-medicines-2-new: no cancel button in the medicine editor"
        )
        cancel.tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    /// Two breastfeeds five minutes apart, joined back into the one feed they
    /// were (BDR-19).
    ///
    /// Its own function rather than a step inside `captureLog`, which every
    /// tile goes through, and numbered under the feeding capture the way
    /// `captureMedicines` shares the treatments number — adding to `logs`
    /// would renumber every file after it. Three captures: the quiet offer on
    /// the later card, the confirmation that names both feeds and shows the one
    /// they would make, and the list with a single feed carrying the five
    /// minutes as a break.
    ///
    /// It writes to the seeded database, so everything captured after it sees
    /// the pair as one feed — which is what the app would show by then.
    private func captureFeedingJoin(_ app: XCUIApplication, language: String) throws {
        // Back up to it: the last log captured sits at the bottom of the grid,
        // and the dashboard comes back scrolled to where that tile was — below
        // the feeding tile, which `scroll(_:to:)` only ever swipes away from.
        let tile = app.buttons["log-tile-feeding"]
        for _ in 0..<6 where !(tile.exists && tile.isHittable) {
            app.swipeDown()
        }
        XCTAssertTrue(
            scroll(app, to: tile),
            "02-feeding-2-join: no feeding tile on the dashboard, even after scrolling"
        )
        tile.tap()

        let join = app.buttons["feeding-join"]
        XCTAssertTrue(
            scroll(app, to: join),
            "02-feeding-2-join: the seeded pair was not offered as a join"
        )
        try file(app, named: "02-feeding-2-join-offered", language: language)

        join.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(
            alert.waitForExistence(timeout: 10),
            "02-feeding-3-join-confirm: tapping Join asked nothing"
        )
        try file(app, named: "02-feeding-3-join-confirm", language: language, waitingFor: alert)

        // By identifier where SwiftUI passes one through to the alert, and
        // otherwise by position — never by label, which is in seven languages.
        // An alert with a cancel button puts it first, so the confirmation is
        // the last. `firstMatch`, because the identifier lands on the alert's
        // action *and* the button view inside it, and a query that matches two
        // elements refuses to tap either.
        let byIdentifier = alert.buttons.matching(identifier: "feeding-join-confirm").firstMatch
        let confirm = byIdentifier.exists
            ? byIdentifier
            : alert.buttons.element(boundBy: alert.buttons.count - 1)
        confirm.tap()

        // The join is a write the list then observes: wait for the offer to go,
        // which is the list redrawn with one feed where there were two.
        let joined = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: join)
        wait(for: [joined], timeout: 10)
        try file(app, named: "02-feeding-4-joined", language: language)

        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    /// Opens one log from the dashboard's grid, files it, and comes back.
    private func captureLog(
        _ app: XCUIApplication,
        tile: String,
        named name: String,
        language: String
    ) throws {
        let button = app.buttons["log-tile-\(tile)"]
        XCTAssertTrue(
            scroll(app, to: button),
            "\(name): no \(tile) tile on the dashboard, even after scrolling"
        )
        button.tap()

        try file(app, named: name, language: language)

        // Back to the dashboard, ready for the next tile. The back button is
        // the navigation bar's first, and its label is the previous screen's
        // title — which is why it is taken by position and not by name.
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    /// Selects a tab, waits for it to settle, and files the image.
    private func capture(
        _ app: XCUIApplication,
        tab index: Int,
        named name: String,
        language: String
    ) throws {
        // A cold simulator can take a while to get the first frame up, and that
        // is not the same event as a screen that never renders. The tab bar gets
        // a generous wait once; everything after it is quick, so a real hang
        // fails fast and says which screen it was on.
        let button = app.tabBars.buttons.element(boundBy: index)
        XCTAssertTrue(
            button.waitForExistence(timeout: 60),
            "\(name): the tab bar never appeared — the app failed to launch"
        )
        button.tap()

        try file(app, named: name, language: language)
    }

    /// Scrolls until `element` is on screen, or gives up.
    ///
    /// Sharing is the fifth of six sections in Settings, so on a phone it starts
    /// below the fold — and the captured image of that screen shows its top. A
    /// SwiftUI `Form` is a `List`, and a `List` builds rows lazily, so a row that
    /// has not been scrolled to may not be in the tree to query at all.
    ///
    /// That is the likelier reading of two runs that could not find the row's
    /// identifier no matter where the identifier was put — likelier, not proven,
    /// because the run's accessibility dump is inside an artifact this
    /// environment cannot fetch. Either way scrolling first is correct: it costs
    /// nothing when the row is already visible.
    ///
    /// The dashboard's log grid is the same story and now proves it. `LogGrid`
    /// is a `LazyVGrid`, so its rows are built as they approach the viewport:
    /// the run that added the medicine card (BDR-16) found *feeding*, *pumping*
    /// and *sleep* and then failed on *diaper* — the first tile of the second
    /// row, pushed out of the build window by one card's height. Nothing about
    /// the grid was wrong; the capture had simply been reading the tiles that
    /// happened to fit.
    ///
    /// **Hittable, not merely present.** A lazy container builds a row shortly
    /// *before* it scrolls into view, so an element can be in the tree while
    /// still off-screen — and tapping one that is throws rather than scrolling
    /// to it. The final `exists` is the fallback: after the swipes are spent,
    /// answering the question this asked before is better than failing a
    /// seven-language run on a judgement about hit-testing.
    private func scroll(_ app: XCUIApplication, to element: XCUIElement, swipes: Int = 6) -> Bool {
        // A short wait first, because swiping at a screen that has not rendered
        // is not scrolling — it just spends the swipes. Short and not the
        // fifteen seconds this replaced: the run's one slow moment is the cold
        // launch, and `capture` already waits sixty seconds for the tab bar
        // before anything reaches here. Every element this is asked about after
        // that is either on screen or below the fold, and the second case is
        // what the loop is for — waiting longer on it would cost the seven
        // languages a minute and a half to learn nothing.
        _ = element.waitForExistence(timeout: 5)
        for _ in 0..<swipes {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        return element.exists
    }

    /// Waits for whatever is on screen to be on screen, then files the image.
    ///
    /// - Parameter anchor: what to wait for. Defaults to the navigation bar,
    ///   which every screen *inside the shell* has — but onboarding runs before
    ///   the shell exists and has none, so a run that waited for one there sat
    ///   for fifteen seconds and filed nothing.
    private func file(
        _ app: XCUIApplication,
        named name: String,
        language: String,
        waitingFor anchor: XCUIElement? = nil
    ) throws {
        // Wait for something rather than sleeping: a fixed delay is either
        // wasted time or a flake, depending on how the runner is feeling.
        //
        // The navigation bar, not a scroll view. Not every screen has a list —
        // the tabs whose screens are not ported yet show a placeholder — and
        // waiting on a thing only some screens have turns "this screen has no
        // list" into "the whole capture failed".
        XCTAssertTrue(
            (anchor ?? app.navigationBars.firstMatch).waitForExistence(timeout: 15),
            "\(name): the screen never appeared"
        )

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "\(language)-\(name)"
        // Without this the attachment is discarded on a passing test, which is
        // every run we actually want the images from.
        attachment.lifetime = .keepAlways
        add(attachment)
    }

}
