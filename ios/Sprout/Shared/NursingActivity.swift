import ActivityKit
import AppIntents
import Foundation

// Compiled into both the app and the Live Activity extension (ADR-0020): the
// extension draws what is described here, and the app is the only thing that
// changes it. No SproutData, so the extension carries no database code at all.

/// A running breastfeed as a Live Activity, on the lock screen and in the
/// Dynamic Island (BDR-24): iOS's form of Android's ongoing notification, because
/// a local notification on iOS is static text and cannot tick.
///
/// Everything it shows is worked out by the app and handed over already in the
/// parent's language — the extension has no string catalog of its own, and a
/// second copy would be a second thing to keep in step.
struct NursingActivityAttributes: ActivityAttributes {
    /// Whose feed this is, by uid — how the app finds the activity again to
    /// update or end it. Never shown: a Live Activity is lock-screen first, so
    /// it never names the baby.
    let babyUid: String
    /// The button labels, keyed by `NursingControl`'s raw value.
    let labels: [String: String]

    struct ContentState: Codable, Hashable {
        /// "Feeding · Left", or "Feed 2 · Left" while more than one is running.
        let title: String
        /// On a break, "On a break · 8m at the breast"; nil while nursing.
        let detail: String?
        let isPaused: Bool
        /// When a clock started then would now read the time at the breast.
        /// The system counts up from here on its own; nothing in the app ticks.
        let clockStart: Date
        /// On a break, where that clock stands still (BDR-17).
        let clockStoppedAt: Date?
        /// The buttons, in order, as `NursingControl` raw values: the app works
        /// them out with `nursingControls(for:)`, the list Android shows.
        let controls: [String]
    }
}

/// A button on the Live Activity: *Pause*, *Switch side*, *Resume left*,
/// *Resume right* or *Stop & save*.
///
/// A `LiveActivityIntent` runs in the app's process, not the extension's, so
/// the act is the app's own — the store, the repository, the same functions the
/// timer screen calls. `NursingActivityControls` is what each side means by
/// performing it: the app's does the work, the extension's is never called.
struct NursingControlIntent: LiveActivityIntent {
    static var title: LocalizedStringResource { "app_name" }
    /// A button on one feed, not something to offer in Shortcuts or Spotlight.
    static var isDiscoverable: Bool { false }

    @Parameter(title: "app_name")
    var babyUid: String

    @Parameter(title: "app_name")
    var control: String

    init() {}

    init(babyUid: String, control: String) {
        self.babyUid = babyUid
        self.control = control
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        await NursingActivityControls.perform(control, babyUid: babyUid.isEmpty ? nil : babyUid)
        return .result()
    }
}
