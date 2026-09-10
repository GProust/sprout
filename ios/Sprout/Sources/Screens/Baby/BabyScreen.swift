import SproutData
import SwiftUI

/// One baby on their own: the same pane the dashboard shows in place when there
/// is only one child, given a tab of its own once there is a choice to make.
///
/// Which baby this is stays the active-baby selection (BDR-0003) — opening a
/// card on the dashboard sets it before navigating here, so the two cannot
/// disagree.
struct BabyScreen: View {
    let model: HomeViewModel
    let onOpen: (LogDestination) -> Void

    var body: some View {
        Group {
            if let summary = model.selectedBaby {
                ScrollView {
                    BabyPane(
                        summary: summary,
                        tracksWellbeing: model.tracksWellbeing,
                        now: model.now,
                        onFeed: { model.startFeed(for: summary.baby, on: $0); onOpen(.feeding) },
                        onOpen: onOpen,
                        onShareRecord: { onOpen(.report) },
                        header: {
                            Text(SproutFormat.age(birthDate: summary.baby.birthDate, now: model.now).text)
                                .font(.callout)
                                .foregroundStyle(SproutColor.onSurfaceVariant)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    )
                    .padding(Spacing.regular)
                }
            } else {
                EmptyHint(Str.t("home_setup_prompt"))
            }
        }
        .navigationTitle(model.selectedBaby?.baby.name ?? Str.t("nav_baby"))
        .navigationBarTitleDisplayMode(.inline)
        .sproutStyle()
    }
}

/// The parent's own tab.
///
/// Pumping and wellbeing are the two logs in Sprout that belong to a *person*
/// rather than to a child — neither carries a `babyId`, by decision, and neither
/// is deleted when a baby is (BDR-0001, BDR-0007). They used to sit in the
/// dashboard's log list next to the baby's screens with nothing to say why, and
/// the daily check-in had nowhere at all once it was dismissed. This is that
/// shelf.
struct YouScreen: View {
    let model: HomeViewModel
    let onOpen: (LogDestination) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.snug) {
                Text(Str.t("you_intro"))
                    .font(.callout)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
                    .padding(.bottom, Spacing.tight)

                YouEntry(
                    systemImage: "drop.triangle.fill",
                    title: Str.t("screen_pumping"),
                    detail: Str.t("you_pumping_body")
                ) { onOpen(.pumping) }

                // Hidden for a parent who has turned their own tracking off, the
                // same way the log grid drops it — the history stays, untouched.
                if model.tracksWellbeing {
                    YouEntry(
                        systemImage: "heart.fill",
                        title: Str.t("screen_wellbeing"),
                        detail: Str.t("you_wellbeing_body")
                    ) { onOpen(.wellbeing) }

                    YouEntry(
                        systemImage: "heart.text.square.fill",
                        title: Str.t("home_checkin_title"),
                        detail: model.checkInPending
                            ? Str.t("home_checkin_body")
                            : Str.t("you_checkin_done")
                    ) { onOpen(.checkIn) }
                }
            }
            .padding(Spacing.regular)
        }
        .navigationTitle(Str.t("nav_you"))
        .navigationBarTitleDisplayMode(.inline)
        .sproutStyle()
    }
}

private struct YouEntry: View {
    let systemImage: String
    let title: String
    /// Not `body` — that name is already taken by the view's own, and a stored
    /// property called `body` shadows it into a compile error.
    let detail: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Spacing.snug + 2) {
                Image(systemName: systemImage)
                    .font(.title3)
                    .foregroundStyle(SproutColor.primary)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(SproutColor.onSurface)
                    Text(detail)
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            .padding(Spacing.regular)
            .background(
                RoundedRectangle(cornerRadius: Radius.card)
                    .stroke(SproutColor.outline.opacity(0.4), lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
