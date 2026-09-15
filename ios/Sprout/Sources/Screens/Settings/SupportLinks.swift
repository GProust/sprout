import Foundation

/// Where a parent can chip in towards Sprout's development — and nothing beyond
/// that (BDR-11). From `ui/settings/SupportLinks.kt`.
///
/// Two properties of this file are load-bearing, and both are easy to undo by
/// accident:
///
/// - **These are links, not payments.** Tapping one hands a URL to whatever
///   browser the phone already has; Sprout opens nothing itself, which is why the
///   app makes no network call at all and declares no networking usage string.
///   Anything that fetched a page, a balance or a supporter count *in* the app
///   would spend the one privacy claim a user can check for themselves.
///   `check_no_network.py` fails the build if such a call appears.
/// - **Nothing is ever given in return.** No feature unlocked, no limit lifted,
///   no badge, no ad removed — there are no ads. A donation that buys something
///   is an in-app purchase, and the App Store then requires it be sold through
///   StoreKit rather than a link out. This is a policy line, not a preference.
enum SupportLinks {

    /// GitHub Sponsors — no platform fee, and the repo's own Sponsor button.
    static let githubSponsors = "https://github.com/sponsors/gproust"

    /// Buy Me a Coffee — a one-off tip, for people who have no GitHub account.
    static let buyMeACoffee = "https://buymeacoffee.com/gproust"

    /// Both destinations, in the order the Settings screen offers them.
    static let all = [githubSponsors, buyMeACoffee]

    /// The destination as a `URL`, or `nil` if one of the constants above was
    /// mistyped.
    ///
    /// Opening it is the *view's* job, through SwiftUI's `openURL` — this file
    /// deliberately imports nothing that could open anything, so the two links
    /// cannot quietly become two requests.
    static func url(_ link: String) -> URL? { URL(string: link) }
}
