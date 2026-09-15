import SproutData
import SwiftUI

/// Expressed milk, from `ui/pumping/PumpingScreen.kt`.
///
/// Belongs to the parent, not to a baby (BDR-0007): no `babyId`, not scoped to
/// the active child, and not deleted when a baby is.
@Observable
@MainActor
final class PumpingViewModel {
    var entries: [Pumping] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeEntries() async {
        await observe(repository.pumpings) { [weak self] in self?.entries = $0 }
    }

    func add(_ entry: Pumping) { try? repository.addPumping(entry) }
    func delete(_ entry: Pumping) { try? repository.deletePumping(entry) }

    /// Giving a bottle is the most common thing to do to a stored batch, so it
    /// is one tap from the list rather than a trip through the editor. Nothing
    /// is lost either way: the entry keeps its amount and time, only its place
    /// changes.
    func markUsed(_ entry: Pumping) {
        var updated = entry
        updated.storage = .USED
        try? repository.addPumping(updated)
    }

    var stash: MilkStash { milkStash(entries: entries, now: Clock.millis) }

    var pumpedTodayMl: Int {
        pumpedSince(entries, from: SproutFormat.startOfDay(Clock.millis))
    }

    var byDay: [(day: Int64, entries: [Pumping])] {
        Dictionary(grouping: entries) { SproutFormat.startOfDay($0.time) }
            .map { (day: $0.key, entries: $0.value) }
            .sorted { $0.day > $1.day }
    }
}

struct PumpingScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: PumpingViewModel?
    @State private var adding = false
    @State private var pendingDelete: Pumping?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_pumping"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? PumpingViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEntries()
        }
    }

    @ViewBuilder
    private func content(_ model: PumpingViewModel) -> some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    StashCard(stash: model.stash, pumpedTodayMl: model.pumpedTodayMl)

                    if model.entries.isEmpty {
                        EmptyHint(Str.t("pumping_empty"))
                    }
                    ForEach(model.byDay, id: \.day) { group in
                        DayHeader(dayStartMillis: group.day, now: Clock.millis)
                        ForEach(group.entries) { entry in
                            PumpingCard(
                                entry: entry,
                                onDelete: { pendingDelete = entry },
                                onMarkUsed: { model.markUsed(entry) }
                            )
                        }
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("pumping_log_title")) { adding = true }
                .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(isPresented: $adding) {
            PumpingForm { model.add($0); adding = false }
        }
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            if let entry = pendingDelete { model.delete(entry) }
            pendingDelete = nil
        }
    }
}

/// What is actually available, and what was made today.
///
/// The totals leave out milk that was used *and* milk past its guidance, so
/// this answers "what can I give the baby" rather than "what have I ever
/// expressed". A number that counted spoiled milk would be worse than none.
private struct StashCard: View {
    let stash: MilkStash
    let pumpedTodayMl: Int

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            SectionLabel(Str.t("pumping_stash"))

            if stash.isEmpty {
                Text(Str.t("pumping_stash_empty"))
                    .font(.callout)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            } else {
                HStack(spacing: Spacing.snug) {
                    if stash.fridgeMl > 0 {
                        StatCard(
                            label: MilkStorage.FRIDGE.label,
                            value: Str.t("feeding_amount_ml", stash.fridgeMl),
                            systemImage: "refrigerator.fill"
                        )
                    }
                    if stash.freezerMl > 0 {
                        StatCard(
                            label: MilkStorage.FREEZER.label,
                            value: Str.t("feeding_amount_ml", stash.freezerMl),
                            systemImage: "snowflake"
                        )
                    }
                    if stash.roomMl > 0 {
                        StatCard(
                            label: MilkStorage.ROOM.label,
                            value: Str.t("feeding_amount_ml", stash.roomMl),
                            systemImage: "thermometer.medium"
                        )
                    }
                }
            }

            Text(Str.t("pumping_today", Str.t("feeding_amount_ml", pumpedTodayMl)))
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)

            // Shown as a reminder, never as a rule the parent has broken: a
            // hospital or a premature baby may be given stricter advice.
            Text(Str.t("pumping_guidance"))
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

private struct PumpingCard: View {
    let entry: Pumping
    let onDelete: () -> Void
    let onMarkUsed: () -> Void

    // Two whole cards rather than one card with a conditional action: an `if`
    // inside the action builder still counts as *having* an action, so a used
    // batch would carry an empty strip of padding under it.
    @ViewBuilder
    var body: some View {
        if entry.isStashed {
            EntryCard(
                title: Str.t("feeding_amount_ml", entry.amountMl),
                subtitle: subtitle,
                meta: SproutDateStyle.time(entry.time),
                systemImage: "drop.triangle.fill",
                onDelete: onDelete,
                details: { EmptyView() },
                action: {
                    // Offered only on milk still in the stash — "mark as used"
                    // on milk already used is a button that does nothing.
                    Button(Str.t("pumping_mark_used"), action: onMarkUsed)
                        .font(.footnote)
                        .foregroundStyle(SproutColor.primary)
                }
            )
        } else {
            EntryCard(
                title: Str.t("feeding_amount_ml", entry.amountMl),
                subtitle: subtitle,
                meta: SproutDateStyle.time(entry.time),
                systemImage: "drop.triangle.fill",
                onDelete: onDelete
            )
        }
    }

    private var subtitle: String {
        var parts = [entry.storage.label]
        if let side = entry.side { parts.append(side.label) }

        if entry.isStashed {
            if isPastBestBefore(entry, now: Clock.millis) {
                parts.append(Str.t("pumping_past_keep"))
            } else if let until = bestBefore(entry) {
                parts.append(Str.t("pumping_keeps_until", SproutDateStyle.dateTime(until)))
            }
        }
        if let notes = entry.notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty {
            parts.append(notes)
        }
        return parts.joined(separator: Str.t("feeding_detail_separator"))
    }
}

private struct PumpingForm: View {
    let onAdd: (Pumping) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var amount = ""
    @State private var side: BreastSide?
    @State private var storage: MilkStorage = .FRIDGE
    @State private var time = Clock.millis
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NumberField(label: Str.t("field_amount"), text: $amount, suffix: "ml")
                    FieldLabel(Str.t("field_side"))
                    ChoiceChips(
                        options: BreastSide.allCases,
                        selection: $side,
                        label: \.label,
                        allowsDeselection: true
                    )
                }
                Section {
                    FieldLabel(Str.t("field_storage"))
                    ChoiceChips(
                        options: MilkStorage.allCases,
                        selection: Binding(get: { storage }, set: { storage = $0 ?? storage }),
                        label: \.label
                    )
                }
                Section {
                    DateTimeField(label: Str.t("field_time"), millis: $time)
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t("pumping_log_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("pumping_add")) {
                        onAdd(
                            Pumping(
                                time: time,
                                amountMl: Int(amount) ?? 0,
                                side: side,
                                storage: storage,
                                notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                            )
                        )
                    }
                    // A pumping session with no milk in it is not a session.
                    .disabled((Int(amount) ?? 0) <= 0)
                }
            }
        }
    }
}
