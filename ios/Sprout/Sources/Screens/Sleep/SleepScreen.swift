import SproutData
import SwiftUI

/// Naps and nights, from `ui/sleep/SleepScreen.kt`.
@Observable
@MainActor
final class SleepViewModel {
    var sleeps: [Sleep] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeSleeps() async {
        await observe(repository.sleeps) { [weak self] in self?.sleeps = $0 }
    }

    func add(_ sleep: Sleep) {
        try? repository.addSleep(sleep)
    }

    func delete(_ sleep: Sleep) {
        try? repository.deleteSleep(sleep)
    }

    /// Close a sleep that was logged as still running.
    ///
    /// Without this the only way out of an ongoing entry is to delete it and
    /// type it again — and while it stays open it goes on counting, because the
    /// dashboard and the statistics both read a missing end as "still asleep".
    func wake(_ sleep: Sleep) {
        var updated = sleep
        updated.endTime = Clock.millis
        try? repository.updateSleep(updated)
    }

    /// Entries grouped by the day they started, newest day first.
    var byDay: [(day: Int64, entries: [Sleep])] {
        Dictionary(grouping: sleeps) { SproutFormat.startOfDay($0.startTime) }
            .map { (day: $0.key, entries: $0.value) }
            .sorted { $0.day > $1.day }
    }
}

struct SleepScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: SleepViewModel?
    @State private var adding = false
    @State private var pendingDelete: Sleep?

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                Color.clear
            }
        }
        .navigationTitle(Str.t("screen_sleep"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? SleepViewModel(repository: sprout.repository)
            self.model = model
            await model.observeSleeps()
        }
    }

    @ViewBuilder
    private func content(_ model: SleepViewModel) -> some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    if model.sleeps.isEmpty {
                        EmptyHint(Str.t("sleep_empty"))
                    }
                    ForEach(model.byDay, id: \.day) { group in
                        DayHeader(dayStartMillis: group.day, now: Clock.millis)
                        ForEach(group.entries) { entry in
                            card(entry, model: model)
                        }
                    }
                }
                .padding(Spacing.regular)
                // Clear of the add button, which floats over the list.
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("sleep_log_title")) { adding = true }
                .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(isPresented: $adding) {
            SleepForm { model.add($0); adding = false }
        }
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            if let entry = pendingDelete { model.delete(entry) }
            pendingDelete = nil
        }
    }

    private func card(_ entry: Sleep, model: SleepViewModel) -> some View {
        EntryCard(
            title: Str.t("sleep_entry_title"),
            subtitle: subtitle(entry),
            meta: SproutDateStyle.time(entry.startTime),
            systemImage: "moon.zzz.fill",
            onDelete: { pendingDelete = entry },
            details: { EmptyView() },
            action: {
                // A sleep still running is the one entry with something left to
                // say; ending it is one tap, from here.
                if entry.isOngoing {
                    Button(Str.t("sleep_woke_up")) { model.wake(entry) }
                        .font(.footnote)
                        .foregroundStyle(SproutColor.primary)
                }
            }
        )
    }

    /// When and how long, then where and how they were lying if that was noted,
    /// then the note itself — one line each.
    private func subtitle(_ entry: Sleep) -> String {
        var lines: [String] = []

        if let end = entry.endTime {
            lines.append(
                Str.t(
                    "sleep_range",
                    SproutDateStyle.time(entry.startTime),
                    SproutDateStyle.time(end),
                    SproutFormat.duration(millis: end - entry.startTime).text
                )
            )
        } else {
            lines.append(Str.t("sleep_ongoing", SproutDateStyle.time(entry.startTime)))
        }

        let details = entry.details
        if !details.isEmpty { lines.append(details) }
        if let notes = entry.notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty {
            lines.append(notes)
        }
        return lines.joined(separator: "\n")
    }
}

extension Sleep {
    /// Where this sleep happened — the parent's own word for it when they gave
    /// one, since a list of six cannot cover where a baby actually falls asleep.
    var placeLabel: String? {
        guard let place else { return nil }
        let named = placeNote?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return place == .OTHER && !named.isEmpty ? named : place.label
    }

    /// "Their own bed · On their back" — whichever of the two was recorded.
    var details: String {
        [placeLabel, position?.label]
            .compactMap { $0 }
            .joined(separator: Str.t("sleep_details_separator"))
    }
}

// MARK: - The form

private struct SleepForm: View {
    let onAdd: (Sleep) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var start = Clock.millis
    @State private var end = Clock.millis
    @State private var hasEnded = true
    @State private var position: SleepPosition?
    @State private var place: SleepPlace?
    @State private var placeNote = ""
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // Naps and nights alike are often logged later, so both
                    // times are pickable. The day moves them together.
                    DateTimeField(label: Str.t("field_start"), millis: $start)
                    Toggle(Str.t("sleep_already_woke"), isOn: $hasEnded)
                    if hasEnded {
                        DateTimeField(label: Str.t("field_end"), millis: $end)
                    }
                }

                Section {
                    FieldLabel(Str.t("sleep_place_label"))
                    // Optional, and not asked twice: tapping the chosen chip
                    // again clears it, so a sleep is still two taps (BDR-0014).
                    ChoiceChips(
                        options: SleepPlace.allCases,
                        selection: $place,
                        label: \.label,
                        allowsDeselection: true
                    )
                    .onChange(of: place) { _, new in
                        if new != .OTHER { placeNote = "" }
                    }
                    if place == .OTHER {
                        TextField(Str.t("sleep_place_other_hint"), text: $placeNote)
                    }

                    FieldLabel(Str.t("sleep_position_label"))
                    ChoiceChips(
                        options: SleepPosition.allCases,
                        selection: $position,
                        label: \.label,
                        allowsDeselection: true
                    )
                }

                Section {
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t("sleep_log_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("sleep_add")) { onAdd(built) }
                }
            }
        }
    }

    private var built: Sleep {
        Sleep(
            startTime: start,
            // Both times are picked on the start's day, so a wake time before
            // the bedtime means the sleep ran past midnight.
            endTime: hasEnded ? (end < start ? SproutFormat.nextDay(end) : end) : nil,
            position: position,
            place: place,
            // Only "somewhere else" carries a name; the other five are named by
            // the app, in whatever language it is showing.
            placeNote: place == .OTHER
                ? placeNote.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                : nil,
            notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
