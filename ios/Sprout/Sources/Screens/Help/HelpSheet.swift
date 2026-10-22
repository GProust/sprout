import SwiftUI

/// The **?** at the trailing end of a page's navigation bar (BDR-26). From
/// `ui/help/Help.kt`.
///
/// Help is on demand and nowhere else: nothing opens it but this button — no
/// first launch, no badge, no tip that appears on its own — because a page
/// that explains itself unasked is a page standing between a parent and the
/// thing they opened it for (BDR-0006).
struct HelpToolbarButton: View {
    let page: HelpPage
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "questionmark.circle")
        }
        .accessibilityLabel(Str.t("cd_help"))
        // By identifier and not by label, like `home-settings`: a UI test runs
        // the same steps in seven languages.
        .accessibilityIdentifier("help-\(page.rawValue)")
    }
}

/// One page's help: each section's heading and words, under the page's name.
///
/// Text only. A screenshot in here would have to be taken again in seven
/// languages and two appearances every time the page behind it changed, and the
/// page is right there behind the sheet anyway. The headings are headers to
/// VoiceOver, so a parent listening can go straight to "Good to know".
struct HelpSheet: View {
    let page: HelpPage

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.loose) {
                    ForEach(helpEntries(page), id: \.section) { entry in
                        VStack(alignment: .leading, spacing: Spacing.tight) {
                            Text(entry.section.title)
                                .font(.headline)
                                .foregroundStyle(SproutColor.onSurface)
                                .accessibilityAddTraits(.isHeader)
                            Text(entry.body)
                                .font(.body)
                                .foregroundStyle(SproutColor.onSurfaceVariant)
                        }
                    }
                }
                .padding(Spacing.regular)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(page.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_close")) { dismiss() }
                        .accessibilityIdentifier("help-close")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// A page's **?** and the sheet behind it, held together so a screen gains
/// help with one line.
struct HelpModifier: ViewModifier {
    let page: HelpPage

    @State private var showing = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    HelpToolbarButton(page: page) { showing = true }
                }
            }
            .sheet(isPresented: $showing) {
                HelpSheet(page: page)
            }
    }
}

extension View {
    /// The page's **?** in its navigation bar, and the sheet it opens (BDR-26).
    ///
    /// Not for the dashboard, whose corner already holds two buttons: it places
    /// its own, last, in the same toolbar as them.
    func helpButton(_ page: HelpPage) -> some View {
        modifier(HelpModifier(page: page))
    }
}
