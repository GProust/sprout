import SproutData
import SwiftUI

/// How many of the household's foods the form offers before anything is typed.
/// Enough for the week's usual suspects; typing reaches the rest.
private let maxRecent = 12

/// How many matches a search shows at once — a word narrows it further.
private let maxMatches = 24

/// Something the parent can tap to put on the meal: a food from Sprout's list, or
/// one the household has logged before. `food` is what it adds; `key` is its
/// ``foodIdentity(_:)``, so the same carrot is never offered twice.
private struct FoodChoice: Identifiable {
    let label: String
    let food: MealFood
    let key: String
    var category: FoodCategory?

    var id: String { key }
}

/// What a search compares: ``foodKey(_:)`` with the accents taken off, so
/// "pates" finds "pâtes" and "epinards" finds "épinards". For searching only —
/// two foods are never merged by it.
private func searchKey(_ text: String) -> String {
    foodKey(text).folding(options: [.diacriticInsensitive], locale: nil)
}

/// The foods of one solid meal (BDR-20), from `ui/foods/MealFoodsEditor.kt`: each
/// one a taste or a serving, and how it went if the parent noticed.
///
/// One field does both jobs. Typing searches Sprout's list — vegetables, fruit,
/// meat and fish, in the phone's language — and every food the household has
/// logged before; the shelves can be browsed without typing at all. Anything that
/// is not found is added as typed (a ratatouille, a grandmother's soup) and is
/// one tap away from then on. A new food starts as a taste; one logged before
/// starts as whatever it was last time.
struct MealFoodsEditor: View {
    @Binding var foods: [MealFood]
    let known: [FoodHistory]

    @State private var typed = ""
    @State private var shelf: FoodCategory?

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
                Image(systemName: "magnifyingglass").foregroundStyle(SproutColor.onSurfaceVariant)
                TextField(Str.t("meal_food_search"), text: $typed)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .onSubmit { addTyped() }
                    .accessibilityIdentifier("meal-food-field")
                Button { addTyped() } label: {
                    Image(systemName: "plus.circle.fill").font(.title3)
                }
                // Borderless, or a Form row fires every button in it at once.
                .buttonStyle(.borderless)
                .disabled(searchKey(typed).isEmpty)
                .accessibilityLabel(Str.t("meal_food_add"))
            }
            .padding(Spacing.snug)
            .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.control))

            // The shelves, to browse without knowing what to type. Tapping
            // the chosen one again puts it away.
            ChoiceChips(
                options: FoodCategory.allCases,
                selection: $shelf,
                label: \.label,
                allowsDeselection: true
            )

            let shown = self.shown
            let offerTyped = !searchKey(typed).isEmpty && exact == nil
            if shelf == nil, searchKey(typed).isEmpty, !shown.isEmpty {
                Text(Str.t("meal_foods_recent"))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            if !shown.isEmpty || offerTyped {
                FlowLayout(spacing: Spacing.tight) {
                    ForEach(shown) { choice in
                        chip(choice.label, systemImage: "plus") { add(choice) }
                    }
                    // Something typed that matches nothing exactly is offered as a
                    // food of its own, by name, so adding the ratatouille is a tap.
                    if offerTyped {
                        chip(
                            Str.t("meal_food_add_named", typed.trimmingCharacters(in: .whitespacesAndNewlines)),
                            systemImage: "pencil"
                        ) { addTyped() }
                    }
                }
            }
        }
    }

    private func chip(_ label: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(label, systemImage: systemImage)
                .font(.callout)
                .padding(.horizontal, Spacing.snug)
                .padding(.vertical, Spacing.tight)
                .overlay(Capsule().stroke(SproutColor.outline.opacity(0.4), lineWidth: 1))
                .foregroundStyle(SproutColor.onSurface)
        }
        .buttonStyle(.plain)
    }

    /// The list in this phone's language.
    private var catalog: [FoodChoice] {
        FoodCatalog.foods.map { item in
            let label = catalogName(item.id) ?? item.id
            return FoodChoice(
                label: label,
                food: MealFood(name: label, ref: item.id),
                key: "ref:\(item.id)",
                category: item.category
            )
        }
    }

    /// Every food the household has logged before, most recent first.
    private var household: [FoodChoice] {
        known.map { history in
            FoodChoice(
                label: history.displayName,
                food: MealFood(name: history.displayName, portion: history.lastPortion, ref: history.ref),
                key: history.key
            )
        }
    }

    private var everything: [FoodChoice] {
        var seen = Set<String>()
        return (household + catalog).filter { seen.insert($0.key).inserted }
    }

    /// The one food that what was typed names exactly, on the list or in the
    /// household — so "carotte" typed out in full is still the carrot from the
    /// list.
    private var exact: FoodChoice? {
        let query = searchKey(typed)
        return everything.first { searchKey($0.label) == query }
    }

    private var shown: [FoodChoice] {
        let query = searchKey(typed)
        let onMeal = Set(foods.map(foodIdentity))
        let choices: [FoodChoice]
        if let shelf {
            choices = catalog
                .filter { $0.category == shelf && (query.isEmpty || searchKey($0.label).contains(query)) }
                .sorted { $0.label.localizedStandardCompare($1.label) == .orderedAscending }
        } else if query.isEmpty {
            choices = Array(household.prefix(maxRecent))
        } else {
            choices = Array(everything.filter { searchKey($0.label).contains(query) }.prefix(maxMatches))
        }
        return choices.filter { !onMeal.contains($0.key) }
    }

    private func add(_ choice: FoodChoice) {
        if !foods.contains(where: { foodIdentity($0) == choice.key }) {
            var food = choice.food
            food.portion = known.first { $0.key == choice.key }?.lastPortion ?? food.portion
            foods.append(food)
        }
        typed = ""
    }

    /// What was typed, as a food: the one it names on the list or in the
    /// household if there is one, and otherwise a food of the household's own.
    private func addTyped() {
        let clean = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if searchKey(clean).isEmpty {
            typed = ""
        } else if let exact {
            add(exact)
        } else {
            add(FoodChoice(label: clean, food: MealFood(name: clean), key: foodKey(clean)))
        }
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
                Text(food.displayName)
                    .font(.body.weight(.medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onRemove) {
                    Image(systemName: "xmark").foregroundStyle(SproutColor.outline)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Str.t("meal_food_remove", food.displayName))
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
