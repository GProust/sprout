import SproutData
import SwiftUI

/// What one announcement says, and the screen *Show me* opens, if any. From
/// `ui/whatsnew/WhatsNew.kt`.
struct AnnouncementContent {
    let title: String
    let body: String
    let destination: LogDestination?
}

/// The words for each id in ``announcementIds``, generated from Android's
/// strings like every other. The keys are literal so that
/// `check_string_keys.py` can see each one.
func announcementContent(_ id: Int) -> AnnouncementContent? {
    switch id {
    case 1:
        return AnnouncementContent(
            title: Str.t("whats_new_1_title"),
            body: Str.t("whats_new_1_body"),
            destination: .foods
        )
    default:
        return nil
    }
}

/// "New in Sprout": the one line the dashboard shows after an update that brings
/// something new (BDR-22).
///
/// It waits on the dashboard rather than standing in front of it: launch still
/// goes straight to the dashboard (BDR-6), and a parent mid-feed can ignore it.
/// The card opens the list. The cross puts it away, and so does reading it.
struct WhatsNewCard: View {
    let newest: Int
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        if let content = announcementContent(newest) {
            HStack(spacing: Spacing.snug) {
                Button(action: onOpen) {
                    HStack(spacing: Spacing.snug) {
                        Image(systemName: "sparkles")
                            .foregroundStyle(SproutColor.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Str.t("whats_new_label"))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(SproutColor.onSurfaceVariant)
                            Text(content.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(SproutColor.onSurface)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home-whats-new")

                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                        .padding(Spacing.hairline)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Str.t("cd_whats_new_dismiss"))
            }
            .padding(Spacing.snug)
            .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
        }
    }
}

/// The announcements themselves, newest first: a title, a few sentences, and
/// *Show me* where there is a screen to go to.
struct WhatsNewSheet: View {
    let ids: [Int]
    let onShowMe: (LogDestination) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.loose) {
                    ForEach(ids, id: \.self) { id in
                        if let content = announcementContent(id) {
                            VStack(alignment: .leading, spacing: Spacing.tight) {
                                Text(content.title)
                                    .font(.headline)
                                    .accessibilityAddTraits(.isHeader)
                                Text(content.body)
                                    .font(.body)
                                    .foregroundStyle(SproutColor.onSurfaceVariant)
                                if let destination = content.destination {
                                    Button(Str.t("whats_new_show_me")) { onShowMe(destination) }
                                        .font(.callout.weight(.medium))
                                }
                            }
                        }
                    }
                }
                .padding(Spacing.regular)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(Str.t("whats_new_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_close")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
