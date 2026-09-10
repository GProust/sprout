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
            sync.waitForExistence(timeout: 10),
            // With the shape of the screen, so a failure says what to query for
            // next rather than only that the query was wrong.
            """
            no sharing row in settings —             cells: \(app.cells.count), buttons: \(app.buttons.count),             staticTexts: \(app.staticTexts.count)
            """
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

    /// Opens one log from the dashboard's grid, files it, and comes back.
    private func captureLog(
        _ app: XCUIApplication,
        tile: String,
        named name: String,
        language: String
    ) throws {
        let button = app.buttons["log-tile-\(tile)"]
        XCTAssertTrue(
            button.waitForExistence(timeout: 15),
            "\(name): no \(tile) tile on the dashboard"
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
