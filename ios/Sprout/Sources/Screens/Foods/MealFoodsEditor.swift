import SproutData
import SwiftUI

/// How many of the household's foods the form offers at once. Enough for the
/// week's usual suspects; typing narrows the rest.
private let maxSuggestions = 12

/// The foods of one solid meal (BDR-20), from `ui/foods/MealFoodsEditor.kt`:
/// each one a taste or a serving, and how it went if the parent noticed.
///
/// There is no list of foods behind this. `known` is the household's own — every
/// food it has logged before, most recent first — offered back as one-tap chips
/// and narrowed as the parent types, so the second time nobody types at all. A
/// new food starts as a taste; one logged before starts as whatever it was last
/// time.
struct MealFoodsEditor: View {
    @Binding var foods: [MealFood]
    let known: [FoodHistory]

    @State private var typed = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            FieldLabel(Str.t("meal_foods"))
            if foods.isEmpty {
                Text(Str.t("meal_foods_hint"))
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            ForEach(Array(foods.enumerated()), id: \.offset) { index, _ in
                MealFoodRow(food: food(at: index)) {
                    if foods.indices.contains(index) { foods.remove(at: index) }
                }
            }

            HStack {
                TextField(Str.t("meal_food_add"), text: $typed)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .onSubmit { add(typed) }
                    .accessibilityIdentifier("meal-food-field")
                Button { add(typed) } label: {
                    Image(systemName: "plus.circle.fill").font(.title3)
                }
                // Borderless, or a Form row fires every button in it at once.
                .buttonStyle(.borderless)
                .disabled(foodKey(typed).isEmpty)
                .accessibilityLabel(Str.t("meal_food_add"))
            }
            .padding(Spacing.snug)
            .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.control))

            let suggestions = self.suggestions
            if !suggestions.isEmpty {
                FlowLayout(spacing: Spacing.tight) {
                    ForEach(suggestions) { history in
                        Button { add(history.name) } label: {
                            Label(history.name, systemImage: "plus")
                                .font(.callout)
                                .padding(.horizontal, Spacing.snug)
                                .padding(.vertical, Spacing.tight)
                                .overlay(Capsule().stroke(SproutColor.outline.opacity(0.4), lineWidth: 1))
                                .foregroundStyle(SproutColor.onSurface)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    /// The household's foods not already on this meal, narrowed by what is
    /// typed.
    private var suggestions: [FoodHistory] {
        let onMeal = Set(foods.map { foodKey($0.name) })
        let query = foodKey(typed)
        return Array(
            known
                .filter { !onMeal.contains($0.key) && (query.isEmpty || $0.key.contains(query)) }
                .prefix(maxSuggestions)
        )
    }

    private func add(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = foodKey(clean)
        // A food already on this meal is not a second offer of it.
        if !key.isEmpty, !foods.contains(where: { foodKey($0.name) == key }) {
            let portion = known.first { $0.key == key }?.lastPortion ?? .TASTE
            foods.append(MealFood(name: clean, portion: portion))
        }
        typed = ""
    }

    /// A binding that survives the row it belongs to being removed: SwiftUI can
    /// ask once more for a row that is on its way out.
    private func food(at index: Int) -> Binding<MealFood> {
        Binding(
            get: { foods.indices.contains(index) ? foods[index] : MealFood(name: "") },
            set: { if foods.indices.contains(index) { foods[index] = $0 } }
        )
    }
}

/// One food on the meal: its name, a taste or a serving, and how it went.
private struct MealFoodRow: View {
    @Binding var food: MealFood
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            HStack {
                Text(food.name)
                    .font(.body.weight(.medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onRemove) {
                    Image(systemName: "xmark").foregroundStyle(SproutColor.outline)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Str.t("meal_food_remove", food.name))
            }
            ChoiceChips(
                options: FoodPortion.allCases,
                selection: Binding(get: { food.portion }, set: { food.portion = $0 ?? food.portion }),
                label: \.label
            )
            // Optional, and tapping the chosen one again clears it: "not noted"
            // is a real answer, and one a parent must be able to go back to.
            ChoiceChips(
                options: FoodReception.allCases,
                selection: $food.reception,
                label: \.label,
                allowsDeselection: true
            )
        }
        .padding(Spacing.snug)
        .overlay(
            RoundedRectangle(cornerRadius: Radius.control)
                .stroke(SproutColor.outline.opacity(0.4), lineWidth: 1)
        )
    }
}

/// A solid meal on its own, new or being corrected — what the First foods page
/// opens. The feeding screen's form logs the same meal with the same editor;
/// this one is held to solids and can also correct one (BDR-20).
struct MealForm: View {
    let initial: Feeding?
    let known: [FoodHistory]
    let onSave: (Feeding) -> Void
    var onDelete: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var foods: [MealFood]
    @State private var grams: String
    @State private var start: Int64
    @State private var notes: String
    @State private var confirmingDelete = false

    init(
        initial: Feeding?,
        known: [FoodHistory],
        onSave: @escaping (Feeding) -> Void,
        onDelete: (() -> Void)? = nil
    ) {
        self.initial = initial
        self.known = known
        self.onSave = onSave
        self.onDelete = onDelete
        _foods = State(initialValue: initial?.mealFoods ?? [])
        _grams = State(initialValue: initial?.amountGrams.map(String.init) ?? "")
        _start = State(initialValue: initial?.startTime ?? Clock.millis)
        _notes = State(initialValue: initial?.notes ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    MealFoodsEditor(foods: $foods, known: known)
                }
                // The meal's weight, optional as it always was: what gets
                // weighed is the bowl, not each food in it.
                Section {
                    NumberField(label: Str.t("field_amount_solid"), text: $grams, suffix: "g")
                }
                Section {
                    DateTimeField(label: Str.t("field_time"), millis: $start)
                    NotesField(text: $notes)
                }
                if onDelete != nil {
                    Section {
                        Button(Str.t("action_delete"), role: .destructive) { confirmingDelete = true }
                    }
                }
            }
            .navigationTitle(initial == nil ? Str.t("foods_log_meal") : Str.t("meal_edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(initial == nil ? Str.t("feeding_add") : Str.t("action_save")) { onSave(built) }
                        .accessibilityIdentifier("meal-save")
                }
            }
            .confirmDelete(isPresented: $confirmingDelete) { onDelete?() }
        }
    }

    /// The meal being corrected keeps its id and its uid, so it stays the same
    /// row here and the same meal on the other phone.
    private var built: Feeding {
        var meal = initial ?? Feeding(type: .SOLID, startTime: start)
        meal.type = .SOLID
        meal.side = nil
        meal.amountMl = nil
        meal.amountGrams = Int(grams)
        meal.startTime = start
        meal.endTime = nil
        meal.mealFoods = foods
        meal.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        return meal
    }
}
