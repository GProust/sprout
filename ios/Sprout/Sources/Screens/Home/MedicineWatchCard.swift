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
/// **One line each, and no more.** The first version of this said everything the
/// As needed screen says — name, dose, interval, the full state sentence, the
/// last dose and its count — and the first thing users said back was that the
/// dashboard had got heavy. They were right: this is a glance at something that
/// is usually not happening, sitting on the screen that is opened every hour. So
/// a line is the name in its state's colour, the number still to wait if there
/// is one, and the two things there are to do about it.
///
/// It keeps its place under the feed buttons rather than above them, for the
/// same reason: feeding is what the dashboard is opened for, and a medicine is
/// what it is opened for a few days a year.
///
/// Everything the line drops is a tap away on the As needed screen — and none of
/// it is dropped for VoiceOver, which is given the full sentence.
struct MedicineWatchCard: View {
    let watches: [MedicineWatch]
    let now: Int64
    let onGive: (MedicineWatch) -> Void
    let onDismiss: (MedicineWatch) -> Void
    let onOpen: () -> Void

    var body: some View {
        if watches.isEmpty {
            EmptyView()
        } else {
            VStack(spacing: 0) {
                ForEach(watches.prefix(watchLimit)) { watch in
                    WatchRow(
                        watch: watch,
                        now: now,
                        onOpen: onOpen,
                        onGive: { onGive(watch) },
                        onDismiss: { onDismiss(watch) }
                    )
                }

                if watches.count > watchLimit {
                    Button(action: onOpen) {
                        Text(Str.t("home_medicine_more", watches.count - watchLimit))
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, Spacing.snug)
                            .padding(.bottom, Spacing.tight)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, Spacing.hairline)
            .background(
                RoundedRectangle(cornerRadius: Radius.card)
                    .stroke(SproutColor.outline.opacity(0.4), lineWidth: 1)
            )
        }
    }
}

/// One medicine, one line: `⏳ Paracetamol  4 h 12 m to wait   Give  ✕`.
///
/// The state is carried three ways over — the symbol's shape, the colour, and
/// the words of the countdown — because red/amber/green is the palette a
/// deuteranope reads worst and this is read at 3 a.m. by someone frightened
/// (BDR-15). The symbol is what distinguishes *can be given* from *can be given,
/// sooner than ideal* once there is no number left to print.
private struct WatchRow: View {
    let watch: MedicineWatch
    let now: Int64
    let onOpen: () -> Void
    let onGive: () -> Void
    let onDismiss: () -> Void

    private var colour: Color { watch.readiness.level.color }

    var body: some View {
        HStack(spacing: Spacing.tight) {
            Button(action: onOpen) {
                HStack(spacing: Spacing.tight) {
                    Image(systemName: watch.readiness.level.symbol)
                        .font(.footnote)
                        .foregroundStyle(colour)
                    Text(watch.medicine.name)
                        .font(.callout.weight(.medium))
                        .foregroundStyle(colour)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if let state = shortState(watch.readiness, now: now) {
                        Text(state)
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                            .lineLimit(1)
                            .layoutPriority(1)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // One utterance, and the long sentence rather than the short one:
            // the line is abbreviated because it is being *looked* at, and none
            // of that applies to VoiceOver.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(
                "\(watch.medicine.name). \(stateSentence(watch.readiness, now: now))"
            )

            // Never disabled, whatever the light says — the same rule the As
            // needed screen keeps. Sprout records what happened; it does not
            // decide it.
            Button(Str.t("medicine_give_short"), action: onGive)
                .font(.footnote.weight(.medium))
                .buttonStyle(.plain)
                .foregroundStyle(SproutColor.primary)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.footnote)
                    .foregroundStyle(SproutColor.outline)
                    .padding(.leading, Spacing.tight)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Str.t("medicine_dismiss"))
        }
        .padding(.horizontal, Spacing.snug)
        .padding(.vertical, Spacing.tight)
    }
}
