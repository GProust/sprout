import SproutData
import SwiftUI

/// How many medicines the dashboard will show before it stops being a glance.
///
/// A household with more waits running than this has a screen for them, and the
/// card says how many it is not showing rather than growing to fit.
private let watchLimit = 3

/// The as-needed medicines with a wait running, or one that has just finished
/// (BDR-16) — the mirror of `ui/home/MedicineWatchCard.kt`.
///
/// It is on the dashboard because that is where a parent already is at 3 a.m.,
/// and "has the paracetamol had six hours yet" is a question asked far more
/// often than it is answered by opening a second screen. It says nothing the
/// as-needed screen doesn't — same sentence, same symbol, same colour, from the
/// same helpers — and it is absent entirely when no wait is running, so the
/// dashboard of a household that is not in the middle of anything is unchanged.
///
/// *Give a dose* is offered here for the same reason it is never disabled
/// there: the dose that goes unlogged is the one the parent had to leave the
/// screen to record.
struct MedicineWatchCard: View {
    let watches: [MedicineWatch]
    let now: Int64
    let onGive: (Medicine) -> Void
    let onOpen: () -> Void

    var body: some View {
        if watches.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: Spacing.snug) {
                Button(action: onOpen) {
                    HStack {
                        Text(Str.t("home_medicine_title"))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(SproutColor.onSurface)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Str.t("screen_medicines"))

                ForEach(watches.prefix(watchLimit)) { watch in
                    WatchRow(watch: watch, now: now) { onGive(watch.medicine) }
                }

                if watches.count > watchLimit {
                    Text(Str.t("home_medicine_more", watches.count - watchLimit))
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
            }
            .padding(Spacing.regular)
            .frame(maxWidth: .infinity, alignment: .leading)
            // Outlined rather than filled: on a household dashboard this sits
            // *inside* a baby's card, and two filled surfaces at the same tone
            // would read as one.
            .background(
                RoundedRectangle(cornerRadius: Radius.card)
                    .stroke(SproutColor.outline.opacity(0.4), lineWidth: 1)
            )
        }
    }
}

/// One medicine's line: the state three ways over — a dot, its own symbol and a
/// sentence — and the dose beside it.
///
/// The three channels are not decoration. Red/amber/green is the palette a
/// deuteranope reads worst, and this card exists to be read at a glance by
/// someone frightened and half awake (BDR-15).
private struct WatchRow: View {
    let watch: MedicineWatch
    let now: Int64
    let onGive: () -> Void

    var body: some View {
        HStack(spacing: Spacing.tight) {
            // Both decorative: the sentence beside them says the same thing,
            // and a screen reader announcing all three would say it three times.
            Circle()
                .fill(watch.readiness.level.color)
                .frame(width: 10, height: 10)
                .accessibilityHidden(true)
            Image(systemName: watch.readiness.level.symbol)
                .foregroundStyle(watch.readiness.level.color)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(watch.medicine.name)
                    .font(.callout)
                    .foregroundStyle(SproutColor.onSurface)
                Text(stateSentence(watch.readiness, now: now))
                    .font(.caption)
                    .foregroundStyle(watch.readiness.level.color)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Never disabled, whatever the light says — the same rule the
            // as-needed screen keeps. Sprout records what happened; it does not
            // decide it.
            Button(Str.t("medicine_give"), action: onGive)
                .font(.footnote.weight(.medium))
                .buttonStyle(.plain)
                .foregroundStyle(SproutColor.primary)
        }
    }
}
