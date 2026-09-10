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
            try captureLog(app, tile: log, named: "0\(index + 2)-\(log)", language: language)
        }

        // Then the other tabs. Three in total with the seeded single baby: the
        // baby's own tab appears only from two children up, because with one the
        // dashboard already is that view.
        try capture(app, tab: 1, named: "08-trends", language: language)
        try capture(app, tab: 2, named: "09-you", language: language)

        // And the check-in, which is reached from the You tab rather than the
        // grid — the one screen a parent is *offered* rather than goes looking
        // for, so worth seeing as it is offered (BDR-0006).
        let checkIn = app.buttons.matching(identifier: "you-entry-checkin").firstMatch
        if checkIn.waitForExistence(timeout: 5) {
            checkIn.tap()
            try file(app, named: "10-checkin", language: language)
        }
    }

    /// The grid's tiles, in the order they are drawn. `treatments` is left out
    /// while its screen is still a placeholder — there is nothing to see, and it
    /// would only be noise in a set whose point is spotting a change.
    private static let logs = ["feeding", "pumping", "sleep", "diaper", "growth", "wellbeing"]

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
    private func file(_ app: XCUIApplication, named name: String, language: String) throws {
        // Wait for something rather than sleeping: a fixed delay is either
        // wasted time or a flake, depending on how the runner is feeling.
        //
        // The navigation bar, not a scroll view. Not every screen has a list —
        // the tabs whose screens are not ported yet show a placeholder — and
        // waiting on a thing only some screens have turns "this screen has no
        // list" into "the whole capture failed".
        XCTAssertTrue(
            app.navigationBars.firstMatch.waitForExistence(timeout: 15),
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
