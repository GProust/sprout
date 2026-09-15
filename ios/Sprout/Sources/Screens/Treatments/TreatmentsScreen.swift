import SproutData
import SwiftUI

/// Medicines and supplements, from `ui/treatments/TreatmentsScreen.kt`.
///
/// Belongs to the baby, so it follows the active one like every other log — and
/// it is the entity that lost its seat in the old tab bar, which is what
/// BDR-0010 was about. It sits in the dashboard's grid now, as an equal.
@Observable
@MainActor
final class TreatmentsViewModel {
    var treatments: [Treatment] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeTreatments() async {
        await observe(repository.treatments) { [weak self] in self?.treatments = $0 }
    }

    /// New or existing, told apart by whether the row has an id yet.
    ///
    /// Android re-arms the alarm here. iOS has no scheduler wired up yet, so
    /// `remindersEnabled` is stored and shown and nothing fires — which the card
    /// does not pretend otherwise about.
    func save(_ treatment: Treatment) {
        if treatment.id == nil {
            _ = try? repository.addTreatment(treatment)
        } else {
            try? repository.updateTreatment(treatment)
        }
    }

    func delete(_ treatment: Treatment) { try? repository.deleteTreatment(treatment) }
}

struct TreatmentsScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: TreatmentsViewModel?
    @State private var editing: Treatment?
    @State private var pendingDelete: Treatment?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_treatments"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? TreatmentsViewModel(repository: sprout.repository)
            self.model = model
            await model.observeTreatments()
        }
    }

    @ViewBuilder
    private func content(_ model: TreatmentsViewModel) -> some View {
        // Courses that are over drop to their own section at the bottom, newest
        // first; the ones still running — or not started yet — stay on top in
        // the query's own order.
        let sections = treatmentSections(model.treatments, now: Clock.millis)

        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    if model.treatments.isEmpty {
                        EmptyHint(Str.t("treatment_empty"))
                    }

                    // A heading only earns its space once there is something to
                    // tell apart: with no finished course the list reads as it
                    // always did.
                    if !sections.past.isEmpty && !sections.current.isEmpty {
                        SectionLabel(Str.t("treatment_section_active"))
                    }
                    ForEach(sections.current) { treatment in
                        TreatmentCard(
                            treatment: treatment,
                            onEdit: { editing = treatment },
                            onDelete: { pendingDelete = treatment }
                        )
                    }

                    if !sections.past.isEmpty {
                        SectionLabel(Str.t("treatment_section_past"))
                        ForEach(sections.past) { treatment in
                            TreatmentCard(
                                treatment: treatment,
                                isPast: true,
                                onEdit: { editing = treatment },
                                onDelete: { pendingDelete = treatment }
                            )
                        }
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("treatment_add")) {
                editing = Treatment(name: "", startDate: Clock.millis)
            }
            .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(item: $editing) { treatment in
            TreatmentEditor(initial: treatment) { model.save($0); editing = nil }
        }
        // Deleting a treatment stops its reminders too, so it is worth asking.
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            title: Str.t("treatment_delete_title", pendingDelete?.name ?? ""),
            message: Str.t("treatment_delete_body")
        ) {
            if let treatment = pendingDelete { model.delete(treatment) }
            pendingDelete = nil
        }
    }
}

/// One treatment.
///
/// A finished course is drawn flat and in the muted pair rather than at a lower
/// alpha: it should read as over at a glance, yet stay legible and still open
/// when tapped. Faded-out text is neither.
private struct TreatmentCard: View {
    let treatment: Treatment
    var isPast = false
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: Spacing.snug) {
            Image(systemName: "pills.fill")
                .font(.title3)
                .foregroundStyle(isPast ? SproutColor.onSurfaceVariant : SproutColor.primary)
                .frame(width: 28)

            Button(action: onEdit) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(isPast ? SproutColor.onSurfaceVariant : SproutColor.onSurface)
                    Text(scheduleSummary(treatment))
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                    if let footnote {
                        Text(footnote)
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundStyle(SproutColor.outline)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Str.t("cd_delete"))
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            isPast ? SproutColor.background : SproutColor.surface,
            in: RoundedRectangle(cornerRadius: Radius.card)
        )
    }

    private var title: String {
        guard let dose = treatment.dose?.trimmingCharacters(in: .whitespacesAndNewlines),
              !dose.isEmpty
        else { return treatment.name }
        return Str.t("treatment_title_dose", treatment.name, dose)
    }

    /// When it ended, or that it will not remind — never both, because only one
    /// of them is true of a course that is over.
    private var footnote: String? {
        if isPast, let end = treatment.endDate {
            return Str.t("treatment_ended", SproutDateStyle.date(end))
        }
        if !treatment.remindersEnabled {
            return Str.t("treatment_reminders_off")
        }
        return nil
    }
}

/// "Every day · 09:00".
private func scheduleSummary(_ treatment: Treatment) -> String {
    let every: String
    switch treatment.intervalDays {
    case 1: every = Str.t("treatment_freq_daily")
    case 7: every = Str.t("treatment_freq_weekly")
    default: every = Str.t("treatment_freq_every_n", treatment.intervalDays)
    }
    let times = treatment.reminderTimes.sorted().map(formatMinute).joined(separator: ", ")
    return Str.t("treatment_schedule_summary", every, times)
}

/// 24-hour and not localised, exactly as on Android: these are the times in a
/// schedule, read as a set, and a column of "9:00 AM / 1:00 PM" is harder to
/// scan than "09:00 / 13:00".
private func formatMinute(_ minute: Int) -> String {
    String(format: "%02d:%02d", minute / 60, minute % 60)
}

// MARK: - The editor

private enum FreqMode: String, CaseIterable {
    case daily, everyN, weekly

    var label: String {
        switch self {
        case .daily: return Str.t("treatment_freq_daily")
        case .everyN: return Str.t("treatment_freq_every_n_label")
        case .weekly: return Str.t("treatment_freq_weekly")
        }
    }

    init(intervalDays: Int) {
        switch intervalDays {
        case 1: self = .daily
        case 7: self = .weekly
        default: self = .everyN
        }
    }
}

private struct TreatmentEditor: View {
    let initial: Treatment
    let onSave: (Treatment) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var dose: String
    @State private var mode: FreqMode
    @State private var customDays: String
    @State private var times: [Int]
    @State private var startDate: Int64
    @State private var hasEnd: Bool
    @State private var endDate: Int64
    @State private var reminders: Bool
    @State private var notes: String

    init(initial: Treatment, onSave: @escaping (Treatment) -> Void) {
        self.initial = initial
        self.onSave = onSave
        let mode = FreqMode(intervalDays: initial.intervalDays)
        _name = State(initialValue: initial.name)
        _dose = State(initialValue: initial.dose ?? "")
        _mode = State(initialValue: mode)
        _customDays = State(initialValue: mode == .everyN ? String(initial.intervalDays) : "2")
        // A treatment with no times is a treatment that reminds you of nothing;
        // nine in the morning is the guess Android makes too.
        _times = State(initialValue: initial.reminderTimes.isEmpty ? [9 * 60] : initial.reminderTimes)
        _startDate = State(initialValue: initial.startDate)
        _hasEnd = State(initialValue: initial.endDate != nil)
        _endDate = State(initialValue: initial.endDate ?? SproutFormat.plusOneYear(initial.startDate))
        _reminders = State(initialValue: initial.remindersEnabled)
        _notes = State(initialValue: initial.notes ?? "")
    }

    private var isNew: Bool { initial.id == nil }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(Str.t("treatment_name"), text: $name)
                    TextField(Str.t("treatment_dose"), text: $dose)
                }

                Section {
                    FieldLabel(Str.t("treatment_frequency"))
                    ChoiceChips(
                        options: FreqMode.allCases,
                        selection: Binding(get: { mode }, set: { mode = $0 ?? mode }),
                        label: \.label
                    )
                    if mode == .everyN {
                        NumberField(
                            label: Str.t("treatment_every_n_days"),
                            text: $customDays,
                            suffix: Str.t("treatment_days_suffix")
                        )
                    }
                }

                Section {
                    FieldLabel(Str.t("treatment_times"))
                    ForEach(times.indices, id: \.self) { index in
                        HStack {
                            TimeField(
                                label: Str.t("picker_at"),
                                millis: Binding(
                                    get: { minuteToMillis(times[index]) },
                                    set: { times[index] = millisToMinute($0) }
                                )
                            )
                            // Never the last one: a schedule with no times is a
                            // treatment that reminds you of nothing.
                            if times.count > 1 {
                                Button {
                                    times.remove(at: index)
                                } label: {
                                    Image(systemName: "trash").foregroundStyle(SproutColor.outline)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Str.t("cd_delete"))
                            }
                        }
                    }
                    Button(Str.t("treatment_add_time")) { times.append(12 * 60) }
                }

                Section {
                    DateField(label: Str.t("treatment_start"), millis: $startDate)
                    Toggle(Str.t("treatment_has_end"), isOn: $hasEnd)
                    if hasEnd {
                        DateField(label: Str.t("treatment_end"), millis: $endDate)
                    }
                }

                Section {
                    Toggle(Str.t("treatment_remind_me"), isOn: $reminders)
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t(isNew ? "treatment_new" : "treatment_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("action_save"), action: save)
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || times.isEmpty)
                }
            }
        }
    }

    private func save() {
        var updated = initial
        updated.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.dose = dose.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        updated.intervalDays = intervalDays
        updated.setReminderTimes(times.sorted())
        updated.startDate = startDate
        updated.endDate = hasEnd ? endDate : nil
        updated.remindersEnabled = reminders
        updated.active = true
        updated.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        onSave(updated)
    }

    private var intervalDays: Int {
        switch mode {
        case .daily: return 1
        case .weekly: return 7
        // Never zero: "every 0 days" is a reminder loop, not a schedule.
        case .everyN: return max(1, Int(customDays) ?? 1)
        }
    }
}

/// The times are minutes since midnight; the picker wants an instant. Any day
/// will do — only the hour and minute are read back.
private func minuteToMillis(_ minute: Int) -> Int64 {
    SproutFormat.settingTime(hour: minute / 60, minute: minute % 60, on: Clock.millis)
}

private func millisToMinute(_ millis: Int64) -> Int {
    let parts = SproutFormat.hourAndMinute(millis)
    return parts.hour * 60 + parts.minute
}
