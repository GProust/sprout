import Combine
import SproutData
import SwiftUI

/// The medicine given when it is needed, and the wait before the next one
/// (BDR-15). From `ui/medicines/MedicinesScreen.kt`. The calendar-shaped
/// counterpart is ``TreatmentsScreen``.
///
/// Nothing on this screen is an assessment. The colours and the sentences
/// restate the intervals the parent typed in, and a dose is always loggable —
/// the button is never disabled and no dialog argues, because a dose given
/// anyway and not recorded is the outcome the whole feature exists to prevent.
@Observable
@MainActor
final class MedicinesViewModel {
    var medicines: [Medicine] = []
    var doses: [MedicineDose] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    /// Both streams at once, in one task group — the same shape the statistics
    /// use. Two `.task` modifiers would start in an order SwiftUI does not
    /// promise, and the second would find the model still nil.
    func observeEverything() async {
        let repository = self.repository
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await observe(repository.medicines) { [weak self] in self?.medicines = $0 }
            }
            group.addTask {
                await observe(repository.medicineDoses) { [weak self] in self?.doses = $0 }
            }
        }
    }

    /// New or existing, told apart by whether the row has an id yet.
    func save(_ medicine: Medicine) {
        if medicine.id == nil {
            _ = try? repository.addMedicine(medicine)
        } else {
            try? repository.updateMedicine(medicine)
        }
    }

    func delete(_ medicine: Medicine) { try? repository.deleteMedicine(medicine) }

    /// Logs a dose of `medicine` as given now, on the baby the medicine belongs
    /// to rather than on whichever one happens to be selected.
    func give(_ medicine: Medicine) {
        _ = try? repository.giveMedicineDose(medicine, at: Clock.millis)
    }

    func updateDose(_ dose: MedicineDose) { try? repository.updateMedicineDose(dose) }

    func deleteDose(_ dose: MedicineDose) { try? repository.deleteMedicineDose(dose) }

    func medicine(forDose dose: MedicineDose) -> Medicine? {
        medicines.first { $0.uid == dose.medicineUid }
    }
}

struct MedicinesScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: MedicinesViewModel?
    @State private var editing: Medicine?
    @State private var editingDose: MedicineDose?
    @State private var pendingDelete: Medicine?

    /// Ticks once a minute so the countdowns stay honest.
    ///
    /// The state is a function of the clock, so a view drawn once and left open
    /// would show "2 h 15 to wait" for as long as the parent looks at it. A
    /// minute is as fine as the sentences get.
    @State private var now = Clock.millis

    private let tick = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_medicines"))
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(tick) { _ in now = Clock.millis }
        .task {
            let model = model ?? MedicinesViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func content(_ model: MedicinesViewModel) -> some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    if model.medicines.isEmpty {
                        EmptyHint(Str.t("medicine_empty"))
                    }

                    ForEach(model.medicines) { medicine in
                        MedicineCard(
                            medicine: medicine,
                            readiness: medicineReadiness(
                                medicine: medicine,
                                doses: model.doses,
                                now: now
                            ),
                            now: now,
                            onGive: { model.give(medicine) },
                            onEdit: { editing = medicine },
                            onDelete: { pendingDelete = medicine }
                        )
                    }

                    // The doses of every medicine in one list, newest first. A
                    // parent checking "what has she had today" is asking across
                    // medicines, not within one.
                    let recent = Array(model.doses.prefix(doseHistoryLimit))
                    if !recent.isEmpty {
                        SectionLabel(Str.t("medicine_history"))
                        ForEach(recent) { dose in
                            EntryCard(
                                title: model.medicine(forDose: dose)?.name
                                    ?? Str.t("medicine_never_given"),
                                subtitle: SproutDateStyle.dateTime(dose.time),
                                meta: dose.notes ?? "",
                                systemImage: "pills.fill",
                                onTap: { editingDose = dose },
                                onDelete: { model.deleteDose(dose) }
                            )
                        }
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("medicine_add")) {
                editing = newMedicine()
            }
            // The screenshot run opens this sheet, and it walks the app in seven
            // languages — so it needs a handle that is not the visible label.
            .accessibilityIdentifier("medicine-add")
            .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(item: $editing) { medicine in
            MedicineEditor(initial: medicine) { model.save($0); editing = nil }
        }
        .sheet(item: $editingDose) { dose in
            DoseEditor(
                dose: dose,
                medicineName: model.medicine(forDose: dose)?.name ?? ""
            ) { model.updateDose($0); editingDose = nil }
        }
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            title: Str.t("medicine_delete_title", pendingDelete?.name ?? ""),
            message: Str.t("medicine_delete_body")
        ) {
            if let medicine = pendingDelete { model.delete(medicine) }
            pendingDelete = nil
        }
    }
}

/// How many doses the history shows before it stops being a history and becomes
/// a scroll.
private let doseHistoryLimit = 50

/// The medicine a new one starts as.
///
/// Paracetamol at six to eight hours, because it is the medicine nearly everyone
/// opens this screen for — and every field is editable, sits under the line
/// saying the numbers come from a prescriber or a leaflet, and is nothing Sprout
/// is asserting about any child (BDR-15).
private func newMedicine() -> Medicine {
    Medicine(name: "", minIntervalMinutes: 6 * 60, comfortIntervalMinutes: 8 * 60)
}

// MARK: - The card

/// One medicine, with where it stands right now.
///
/// The state is drawn three ways at once — a coloured dot, its own symbol, and a
/// sentence — because red/amber/green is exactly the palette a deuteranope reads
/// worst, and this is not a chart that can be studied at leisure (BDR-15). The
/// sentence is what the card leads with; the colour agrees with it.
private struct MedicineCard: View {
    let medicine: Medicine
    let readiness: MedicineReadiness
    let now: Int64
    let onGive: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            HStack(spacing: Spacing.snug) {
                Button(action: onEdit) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.body.weight(.medium))
                            .foregroundStyle(SproutColor.onSurface)
                        Text(intervalSummary(medicine))
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Button(action: onDelete) {
                    Image(systemName: "trash").foregroundStyle(SproutColor.outline)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Str.t("cd_delete"))
            }

            HStack(spacing: Spacing.tight) {
                Circle()
                    .fill(readiness.level.color)
                    .frame(width: 10, height: 10)
                Image(systemName: readiness.level.symbol)
                    .foregroundStyle(readiness.level.color)
                Text(stateSentence(readiness, now: now))
                    .font(.callout)
                    .foregroundStyle(readiness.level.color)
            }
            // The dot and the symbol say what the sentence beside them already
            // does; announcing all three would say it three times.
            .accessibilityElement(children: .combine)
            .accessibilityLabel(stateSentence(readiness, now: now))

            Text(lastDoseLine(readiness))
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)

            // Never disabled, whatever the light says. Sprout records what
            // happened; a prescriber may well have said otherwise, and a dose
            // given but not logged is the failure this screen exists to prevent.
            Button(Str.t("medicine_give"), action: onGive)
                .buttonStyle(.borderedProminent)
                .tint(SproutColor.primary)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    private var title: String {
        guard let dose = medicine.dose?.trimmingCharacters(in: .whitespacesAndNewlines),
              !dose.isEmpty
        else { return medicine.name }
        return Str.t("treatment_title_dose", medicine.name, dose)
    }
}

// MARK: - The editors

private struct MedicineEditor: View {
    let initial: Medicine
    let onSave: (Medicine) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var dose: String
    @State private var minHours: String
    @State private var comfortHours: String
    @State private var maxPerDay: String
    @State private var remind: Bool
    @State private var remindAtComfort: Bool
    @State private var notes: String

    init(initial: Medicine, onSave: @escaping (Medicine) -> Void) {
        self.initial = initial
        self.onSave = onSave
        _name = State(initialValue: initial.name)
        _dose = State(initialValue: initial.dose ?? "")
        _minHours = State(initialValue: hoursText(initial.minIntervalMinutes))
        _comfortHours = State(
            initialValue: initial.comfortIntervalMinutes.map(hoursText) ?? ""
        )
        _maxPerDay = State(initialValue: initial.maxPerDay.map(String.init) ?? "")
        _remind = State(initialValue: initial.remindWhenDue)
        _remindAtComfort = State(initialValue: initial.remindAtComfort)
        _notes = State(initialValue: initial.notes ?? "")
    }

    private var isNew: Bool { initial.id == nil }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(Str.t("medicine_name"), text: $name)
                    TextField(Str.t("medicine_dose"), text: $dose)
                }

                Section {
                    FieldLabel(Str.t("medicine_intervals"))
                    // Above the fields, not below them: it is the sentence that
                    // decides what a parent types into them.
                    Text(Str.t("medicine_intervals_hint"))
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                    LabelledNumberField(
                        label: Str.t("medicine_min_interval"),
                        text: $minHours,
                        suffix: Str.t("medicine_hours_suffix")
                    )
                    LabelledNumberField(
                        label: Str.t("medicine_comfort_interval"),
                        text: $comfortHours,
                        suffix: Str.t("medicine_hours_suffix")
                    )
                    LabelledNumberField(
                        label: Str.t("medicine_max_per_day"),
                        text: $maxPerDay,
                        suffix: Str.t("medicine_doses_suffix")
                    )
                }

                Section {
                    Toggle(Str.t("medicine_remind"), isOn: $remind)
                    // The second boundary only exists when there are two, so the
                    // choice only appears then.
                    if remind && !comfortHours.isEmpty {
                        FieldLabel(Str.t("medicine_remind_at"))
                        ChoiceChips(
                            options: [false, true],
                            selection: Binding(
                                get: { remindAtComfort },
                                set: { remindAtComfort = $0 ?? remindAtComfort }
                            ),
                            label: { atComfort in
                                Str.t(atComfort ? "medicine_remind_at_comfort" : "medicine_remind_at_min")
                            }
                        )
                    }
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t(isNew ? "medicine_new" : "medicine_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                        .accessibilityIdentifier("medicine-editor-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("action_save"), action: save)
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func save() {
        var updated = initial
        updated.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.dose = dose.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        // Never zero: a minimum wait of no time at all is not a wait.
        updated.minIntervalMinutes = max(1, Int(minHours) ?? 1) * 60
        updated.comfortIntervalMinutes = Int(comfortHours).flatMap { hours -> Int? in
            hours > 0 ? hours * 60 : nil
        }
        updated.maxPerDay = Int(maxPerDay).flatMap { limit -> Int? in limit > 0 ? limit : nil }
        updated.remindWhenDue = remind
        updated.remindAtComfort = remindAtComfort
        updated.active = true
        updated.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        onSave(updated)
    }
}

/// A number field with its label beside it rather than inside it.
///
/// ``NumberField`` puts its label in the placeholder, which is right for every
/// other field in the app because they all start empty. These three do not: the
/// minimum and the usual wait open prefilled (BDR-15), so the placeholder is
/// never drawn and the form showed "6 hours" above "8 hours" with nothing to say
/// which was which.
///
/// Found by looking at the screenshot, which is what they are for. Android is
/// unaffected — Material floats its label above a filled field rather than
/// hiding it.
private struct LabelledNumberField: View {
    let label: String
    @Binding var text: String
    let suffix: String

    var body: some View {
        HStack(spacing: Spacing.snug) {
            Text(label)
                .foregroundStyle(SproutColor.onSurface)
            Spacer(minLength: Spacing.tight)
            // Wide enough for a two-digit answer and its unit, narrow enough to
            // leave the label room to wrap rather than truncate.
            NumberField(label: "", text: $text, suffix: suffix)
                .frame(maxWidth: 150)
        }
    }
}

/// Correcting a dose — the time it was given, and a note.
///
/// Worth its own sheet because the common correction is a real one: the dose was
/// given at 2 a.m. and logged at 6, and the traffic light is wrong by four hours
/// until someone can say so.
private struct DoseEditor: View {
    let dose: MedicineDose
    let medicineName: String
    let onSave: (MedicineDose) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var time: Int64
    @State private var notes: String

    init(dose: MedicineDose, medicineName: String, onSave: @escaping (MedicineDose) -> Void) {
        self.dose = dose
        self.medicineName = medicineName
        self.onSave = onSave
        _time = State(initialValue: dose.time)
        _notes = State(initialValue: dose.notes ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(Str.t("medicine_of", medicineName))
                        .font(.body.weight(.medium))
                    DateTimeField(label: Str.t("medicine_dose_time"), millis: $time)
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t("medicine_dose_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("action_save")) {
                        var updated = dose
                        updated.time = time
                        updated.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                        onSave(updated)
                    }
                }
            }
        }
    }
}

/// Minutes as whole hours for the editor's fields.
///
/// The fields are in hours because that is the unit every leaflet uses, and a
/// medicine whose stored interval is not a whole number of hours — which nothing
/// in the app can currently produce — rounds down rather than showing a blank.
private func hoursText(_ minutes: Int) -> String { String(max(1, minutes / 60)) }
