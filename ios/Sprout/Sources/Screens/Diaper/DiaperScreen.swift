import SproutData
import SwiftUI

/// Nappy changes, from `ui/diaper/DiaperScreen.kt`.
@Observable
@MainActor
final class DiaperViewModel {
    var diapers: [Diaper] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeDiapers() async {
        await observe(repository.diapers) { [weak self] in self?.diapers = $0 }
    }

    func add(_ diaper: Diaper) { try? repository.addDiaper(diaper) }
    func delete(_ diaper: Diaper) { try? repository.deleteDiaper(diaper) }

    var byDay: [(day: Int64, entries: [Diaper])] {
        Dictionary(grouping: diapers) { SproutFormat.startOfDay($0.time) }
            .map { (day: $0.key, entries: $0.value) }
            .sorted { $0.day > $1.day }
    }
}

struct DiaperScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: DiaperViewModel?
    @State private var adding = false
    @State private var pendingDelete: Diaper?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_diapers"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? DiaperViewModel(repository: sprout.repository)
            self.model = model
            await model.observeDiapers()
        }
    }

    @ViewBuilder
    private func content(_ model: DiaperViewModel) -> some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    if model.diapers.isEmpty {
                        EmptyHint(Str.t("diaper_empty"))
                    }
                    ForEach(model.byDay, id: \.day) { group in
                        DayHeader(dayStartMillis: group.day, now: Clock.millis)
                        ForEach(group.entries) { entry in
                            EntryCard(
                                title: entry.title,
                                subtitle: entry.subtitle,
                                meta: SproutDateStyle.time(entry.time),
                                systemImage: "figure.child",
                                onDelete: { pendingDelete = entry }
                            )
                        }
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("diaper_log_title")) { adding = true }
                .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(isPresented: $adding) {
            DiaperForm { model.add($0); adding = false }
        }
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            if let entry = pendingDelete { model.delete(entry) }
            pendingDelete = nil
        }
    }
}

extension Diaper {
    /// From the checklist: "Urine", "Stool", or "Urine + stool".
    var title: String {
        if wet && dirty { return Str.t("diaper_both") }
        if dirty { return Str.t("diaper_stool") }
        return Str.t("diaper_urine")
    }

    /// Stool colour, if any, and the note.
    var subtitle: String {
        var parts: [String] = []
        if dirty, let stoolColor { parts.append(stoolColor.label) }
        if let notes = notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty {
            parts.append(notes)
        }
        return parts.joined(separator: Str.t("diaper_separator"))
    }
}

extension StoolColor {
    var label: String {
        switch self {
        case .YELLOW: return Str.t("stool_color_yellow")
        case .GREEN: return Str.t("stool_color_green")
        case .BROWN: return Str.t("stool_color_brown")
        case .PALE: return Str.t("stool_color_pale")
        case .CLAY: return Str.t("stool_color_clay")
        case .WHITE: return Str.t("stool_color_white")
        case .BLACK: return Str.t("stool_color_black")
        case .RED: return Str.t("stool_color_red")
        }
    }

    /// The swatch, approximating the infant stool colour card.
    ///
    /// These are **reference values, not decoration**: the pale/acholic range is
    /// what the printed cards exist to flag, and a parent comparing a nappy to
    /// this scale is doing the thing the scale is for. They do not change with
    /// the appearance, because a colour that shifted in dark mode would be
    /// comparing against the wrong card.
    var swatch: Color {
        switch self {
        case .YELLOW: return Color(hex: 0xE7C24B)
        case .GREEN: return Color(hex: 0x7C8A4A)
        case .BROWN: return Color(hex: 0x7A5230)
        case .PALE: return Color(hex: 0xEADFB4)
        case .CLAY: return Color(hex: 0xD7D0BE)
        case .WHITE: return Color(hex: 0xF1EFE8)
        case .BLACK: return Color(hex: 0x36322C)
        case .RED: return Color(hex: 0xB23A2E)
        }
    }

    /// Pale swatches need a dark check mark to stay legible.
    var isLight: Bool {
        switch self {
        case .YELLOW, .PALE, .CLAY, .WHITE: return true
        default: return false
        }
    }
}

// MARK: - The form

private struct DiaperForm: View {
    let onAdd: (Diaper) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var wet = true
    @State private var dirty = false
    @State private var stoolColor: StoolColor?
    @State private var time = Clock.millis
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FieldLabel(Str.t("diaper_contents"))
                    Toggle(Str.t("diaper_urine"), isOn: $wet)
                    Toggle(Str.t("diaper_stool"), isOn: $dirty)
                        .onChange(of: dirty) { _, isDirty in
                            // A colour with no stool to describe is a colour
                            // nobody chose; clearing it keeps the row honest.
                            if !isDirty { stoolColor = nil }
                        }
                }

                if dirty {
                    Section {
                        FieldLabel(Str.t("diaper_stool_color"))
                        StoolColorPicker(selection: $stoolColor)
                    }
                }

                Section {
                    DateTimeField(label: Str.t("field_time"), millis: $time)
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t("diaper_log_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("diaper_add")) { onAdd(built) }
                        // Neither box ticked is not a nappy change.
                        .disabled(!wet && !dirty)
                }
            }
        }
    }

    private var built: Diaper {
        Diaper(
            time: time,
            wet: wet,
            dirty: dirty,
            stoolColor: dirty ? stoolColor : nil,
            notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }
}

/// The colour scale, drawn as swatches rather than named chips.
///
/// The order is the one the card uses: the common healthy colours first, then
/// the pale range those cards exist to flag, then the blood-related ones. A
/// parent holds the nappy next to the phone and matches it, so the swatch has to
/// be the thing they tap — a list of colour *names* would be asking them to
/// translate what they are looking at into a word first.
private struct StoolColorPicker: View {
    @Binding var selection: StoolColor?

    var body: some View {
        FlowLayout(spacing: Spacing.snug) {
            ForEach(StoolColor.allCases, id: \.self) { colour in
                let isSelected = selection == colour
                Button {
                    selection = isSelected ? nil : colour
                } label: {
                    ZStack {
                        Circle()
                            .fill(colour.swatch)
                            .frame(width: 44, height: 44)
                            .overlay(
                                Circle().stroke(
                                    isSelected ? SproutColor.primary : SproutColor.outline.opacity(0.5),
                                    lineWidth: isSelected ? 3 : 1
                                )
                            )
                        if isSelected {
                            Image(systemName: "checkmark")
                                .font(.headline)
                                .foregroundStyle(colour.isLight ? .black : .white)
                        }
                    }
                }
                .buttonStyle(.plain)
                // The swatch is the whole control, so the name has to reach
                // VoiceOver some other way.
                .accessibilityLabel(colour.label)
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }
        }
    }
}
