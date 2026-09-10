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

        // The four tabs (BDR-0010), then the logs that are reached by pushing
        // from the dashboard's grid rather than by a tab of their own.
        try capture(app, tab: 0, named: "01-home", language: language)
        try capture(app, tab: 1, named: "02-trends", language: language)
        try capture(app, tab: 2, named: "03-you", language: language)
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
}
