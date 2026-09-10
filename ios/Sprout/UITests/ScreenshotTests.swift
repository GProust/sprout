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

        try capture(app, tab: "nav_home_tab", named: "01-home", language: language)
        try capture(app, tab: "nav_feed_tab", named: "02-feeding", language: language)
        try capture(app, tab: "nav_sleep_tab", named: "03-sleep", language: language)
        try capture(app, tab: "nav_diaper_tab", named: "04-diaper", language: language)
        try capture(app, tab: "nav_growth_tab", named: "05-growth", language: language)
    }

    /// Selects a tab, waits for it to settle, and files the image.
    private func capture(
        _ app: XCUIApplication,
        tab identifier: String,
        named name: String,
        language: String
    ) throws {
        // A cold simulator can take a while to get the first frame up, and that
        // is not the same event as a screen that never renders. The tab bar gets
        // a generous wait once; everything after it is quick, so a real hang
        // fails fast and says which screen it was on.
        let button = app.tabBars.buttons.element(boundBy: tabIndex(for: identifier))
        XCTAssertTrue(
            button.waitForExistence(timeout: 60),
            "\(name): the tab bar never appeared — the app failed to launch"
        )
        button.tap()

        // Wait for a cell rather than sleeping: a fixed delay is either wasted
        // time or a flake, depending on how the runner is feeling.
        XCTAssertTrue(
            app.scrollViews.firstMatch.waitForExistence(timeout: 15),
            "\(name): the screen's list never appeared"
        )

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "\(language)-\(name)"
        // Without this the attachment is discarded on a passing test, which is
        // every run we actually want the images from.
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func tabIndex(for identifier: String) -> Int {
        switch identifier {
        case "nav_home_tab": return 0
        case "nav_feed_tab": return 1
        case "nav_sleep_tab": return 2
        case "nav_diaper_tab": return 3
        case "nav_growth_tab": return 4
        default: return 0
        }
    }
}
