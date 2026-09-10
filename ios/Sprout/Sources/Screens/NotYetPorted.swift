import SwiftUI

/// A screen the Android app has and this one does not yet.
///
/// Deliberately an honest placeholder rather than an empty view or a screen that
/// half-works. Two reasons:
///
/// - **The screenshots are the review**, and a blank screen photographs exactly
///   like a broken one. This says which it is.
/// - The navigation this sits under is real (BDR-0010) and worth reviewing on
///   its own — where the logs live, what the four tabs are, which screens are
///   pushed rather than tabbed. Stubbing the destinations is what lets that
///   structure be looked at before every screen behind it exists.
///
/// Each of these is a tracked gap, listed in the pull request. When the screen
/// lands, the case in `RootView.screen(for:)` changes and this disappears with
/// it.
struct NotYetPorted: View {
    let title: String
    /// Roughly how much Kotlin there is to bring across — a reviewer's guide to
    /// what is left, not a promise.
    let androidLines: Int

    var body: some View {
        VStack(spacing: Spacing.snug) {
            Image(systemName: "hammer.fill")
                .font(.largeTitle)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(SproutColor.onSurface)
            Text("Not ported from Android yet — \(androidLines) lines to bring across.")
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .padding(Spacing.section)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .sproutStyle()
    }
}

// The gaps, each named so a screenshot says which screen is missing rather than
// showing an empty one.

struct ReportScreen: View {
    var body: some View { NotYetPorted(title: Str.t("report_screen_title"), androidLines: 708) }
}

struct SettingsScreen: View {
    var body: some View { NotYetPorted(title: Str.t("screen_settings"), androidLines: 684) }
}

struct SyncScreen: View {
    var body: some View { NotYetPorted(title: Str.t("screen_sync"), androidLines: 802) }
}
