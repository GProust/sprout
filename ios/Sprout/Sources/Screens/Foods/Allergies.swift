import SproutData
import SwiftUI

// A baby's allergies on the First foods page (BDR-21). From `ui/foods/Allergies.kt`.

extension Allergy {
    /// What the allergy is to, in this phone's language when it is a food from
    /// the list.
    var displayName: String { catalogName(ref) ?? name }

    /// "Confirmed by a health professional" or "Noted by the family" — the
    /// parent's own statement, said back.
    var statusLabel: String {
        Str.t(confirmed ? "allergy_confirmed_short" : "allergy_noted_short")
    }

    /// "Confirmed by a health professional · Noticed 3 Sep 2026" — whichever
    /// parts were given.
    var details: String {
        var parts = [statusLabel]
        if let noticedAt { parts.append(Str.t("allergy_noticed_line", SproutDateStyle.date(noticedAt))) }
        return parts.joined(separator: Str.t("feeding_detail_separator"))
    }
}

/// The mark beside a food the baby is noted as allergic to: a symbol and the
/// words, never a colour alone and never a warning colour — it restates what the
/// parent wrote down, it does not raise an alarm of its own.
struct AllergyMarker: View {
    var body: some View {
        Label(Str.t("allergy_marker"), systemImage: "allergens")
            .font(.caption.weight(.medium))
            .foregroundStyle(SproutColor.onSurface)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Str.t("allergy_marker"))
    }
}

/// The baby's allergies at the top of the First foods page: each one opens to
/// correct, *Note an allergy* adds one, and *Share the list* hands the list on
/// its own to whoever needs it.
struct AllergiesCard: View {
    let allergies: [Allergy]
    let onAdd: () -> Void
    let onOpen: (Allergy) -> Void
    let onShare: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            Label(Str.t("allergies_title"), systemImage: "allergens")
                .font(.headline)
                .foregroundStyle(SproutColor.onSurface)
            if allergies.isEmpty {
                Text(Str.t("allergies_none"))
                    .font(.callout)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            ForEach(allergies) { allergy in
                Button { onOpen(allergy) } label: {
                    VStack(alignment: .leading, spacing: Spacing.hairline) {
                        Text(allergy.displayName)
                            .font(.body.weight(.medium))
                            .foregroundStyle(SproutColor.onSurface)
                        Text(allergy.details)
                            .font(.footnote)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            HStack(spacing: Spacing.regular) {
                Button(action: onAdd) {
                    Label(Str.t("allergy_add"), systemImage: "plus")
                }
                if !allergies.isEmpty {
                    Button(action: onShare) {
                        Label(Str.t("allergies_share"), systemImage: "square.and.arrow.up")
                    }
                    .accessibilityIdentifier("allergies-share")
                }
            }
            .font(.footnote)
            .buttonStyle(.plain)
            .foregroundStyle(SproutColor.primary)
            .padding(.top, Spacing.hairline)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

/// Noting an allergy, or correcting one: what it is to — picked with the same
/// search the meal form uses, so a carrot from the list is the carrot on every
/// meal — and, optionally, when it was noticed, whether a health professional
/// confirmed it, and what was seen. Nothing is suggested, and nothing asks about
/// symptoms: what the parent saw goes in the notes, in their words.
struct AllergyForm: View {
    let initial: Allergy?
    let known: [FoodHistory]
    let onSave: (Allergy) -> Void
    var onDelete: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var food: MealFood?
    @State private var hasDate: Bool
    @State private var noticedAt: Int64
    @State private var confirmed: Bool
    @State private var notes: String
    @State private var confirmingDelete = false

    init(
        initial: Allergy?,
        known: [FoodHistory],
        onSave: @escaping (Allergy) -> Void,
        onDelete: (() -> Void)? = nil
    ) {
        self.initial = initial
        self.known = known
        self.onSave = onSave
        self.onDelete = onDelete
        _food = State(initialValue: initial.map { MealFood(name: $0.displayName, ref: $0.ref) })
        _hasDate = State(initialValue: initial?.noticedAt != nil)
        _noticedAt = State(initialValue: initial?.noticedAt ?? Clock.millis)
        _confirmed = State(initialValue: initial?.confirmed ?? false)
        _notes = State(initialValue: initial?.notes ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FieldLabel(Str.t("allergy_what"))
                    if let food {
                        HStack {
                            Text(food.name)
                                .font(.body.weight(.medium))
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Button(Str.t("food_correct_change")) { self.food = nil }
                                .buttonStyle(.borderless)
                        }
                    } else {
                        FoodSearch(known: known, hidden: []) { food = $0 }
                    }
                }
                Section {
                    if hasDate {
                        HStack {
                            DateField(label: Str.t("allergy_noticed"), millis: $noticedAt)
                            Button { hasDate = false } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(SproutColor.outline)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(Str.t("allergy_noticed_clear"))
                        }
                    } else {
                        Button(Str.t("allergy_noticed_add")) {
                            noticedAt = Clock.millis
                            hasDate = true
                        }
                    }
                    Toggle(isOn: $confirmed) {
                        VStack(alignment: .leading, spacing: Spacing.hairline) {
                            Text(Str.t("allergy_confirmed"))
                            Text(Str.t("allergy_confirmed_hint"))
                                .font(.caption)
                                .foregroundStyle(SproutColor.onSurfaceVariant)
                        }
                    }
                }
                Section {
                    NotesField(text: $notes)
                    Text(Str.t("allergy_hint"))
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
                if onDelete != nil {
                    Section {
                        Button(Str.t("action_delete"), role: .destructive) { confirmingDelete = true }
                    }
                }
            }
            .navigationTitle(initial == nil ? Str.t("allergy_add") : Str.t("allergy_edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("action_save")) { if let built { onSave(built) } }
                        .disabled(food == nil)
                }
            }
            .confirmDelete(isPresented: $confirmingDelete) { onDelete?() }
        }
    }

    /// The allergy being corrected keeps its id and its uid, so it stays the same
    /// row here and the same allergy on the other phone.
    private var built: Allergy? {
        guard let food else { return nil }
        var allergy = initial ?? Allergy(name: food.name)
        allergy.name = food.name
        allergy.ref = food.ref
        allergy.noticedAt = hasDate ? noticedAt : nil
        allergy.confirmed = confirmed
        let trimmed = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        allergy.notes = trimmed.isEmpty ? nil : trimmed
        return allergy
    }
}
