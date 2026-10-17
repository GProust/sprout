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

/// How short a name on the list may be and still be found inside a longer word:
/// "carotte" inside "carottes" or "purée de carottes", but not "pois" — four
/// letters — inside "poisson". A search hint only; nothing is merged by it.
private let minContained = 5

/// The foods of one solid meal (BDR-20), from `ui/foods/MealFoodsEditor.kt`: each
/// one a taste or a serving, and how it went if the parent noticed.
///
/// Below the meal's foods, ``FoodSearch`` finds what to add. A new food starts as
/// a taste; one logged before starts as whatever it was last time.
struct MealFoodsEditor: View {
    @Binding var foods: [MealFood]
    let known: [FoodHistory]
    /// The foods the baby is noted as allergic to (BDR-21): marked, and nothing
    /// else changes for them.
    var allergies: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            FieldLabel(Str.t("meal_foods"))
            if foods.isEmpty {
                Text(Str.t("meal_foods_hint"))
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            ForEach(Array(foods.enumerated()), id: \.offset) { index, item in
                MealFoodRow(food: food(at: index), allergic: allergies.contains(foodIdentity(item))) {
                    if foods.indices.contains(index) { foods.remove(at: index) }
                }
            }
            FoodSearch(known: known, hidden: Set(foods.map(foodIdentity))) { picked in
                var food = picked
                food.portion = known.first { $0.key == foodIdentity(picked) }?.lastPortion ?? food.portion
                foods.append(food)
            }
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

/// One field that finds a food, from `FoodSearch` in `MealFoodsEditor.kt`: typing
/// searches Sprout's list — vegetables, fruit, meat and fish, in the phone's
/// language — and every food the household has logged before; the shelves can be
/// browsed without typing at all. Anything that is not found is offered as typed
/// (a ratatouille, a grandmother's soup) and is one tap away from then on.
///
/// A word longer than a name on the list still finds it: "carottes" offers the
/// carrot from the list first, above "carottes" as typed, so a plural does not
/// quietly become a food of its own. `hidden` are the foods (by
/// ``foodIdentity(_:)``) not to offer — the ones already on the meal.
struct FoodSearch: View {
    let known: [FoodHistory]
    let hidden: Set<String>
    let onPick: (MealFood) -> Void

    @State private var typed = ""
    @State private var shelf: FoodCategory?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(SproutColor.onSurfaceVariant)
                TextField(Str.t("meal_food_search"), text: $typed)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .onSubmit { pickTyped() }
                    .accessibilityIdentifier("meal-food-field")
                Button { pickTyped() } label: {
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
                        chip(choice.label, systemImage: "plus") { pick(choice) }
                    }
                    // Something typed that matches nothing exactly is offered as a
                    // food of its own, by name, so adding the ratatouille is a tap.
                    // It comes after the matches: "carottes" is far more often the
                    // carrot than a food of its own.
                    if offerTyped {
                        chip(
                            Str.t("meal_food_add_named", typed.trimmingCharacters(in: .whitespacesAndNewlines)),
                            systemImage: "pencil"
                        ) { pickTyped() }
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
        let choices: [FoodChoice]
        if let shelf {
            choices = catalog
                .filter { $0.category == shelf && (query.isEmpty || searchKey($0.label).contains(query)) }
                .sorted { $0.label.localizedStandardCompare($1.label) == .orderedAscending }
        } else if query.isEmpty {
            choices = Array(household.prefix(maxRecent))
        } else {
            choices = Array(matches(everything, query).prefix(maxMatches))
        }
        return choices.filter { !hidden.contains($0.key) }
    }

    private func pick(_ choice: FoodChoice) {
        if !hidden.contains(choice.key) { onPick(choice.food) }
        typed = ""
    }

    /// What was typed, as a food: the one it names on the list or in the
    /// household if there is one, and otherwise a food of the household's own.
    private func pickTyped() {
        let clean = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if searchKey(clean).isEmpty {
            typed = ""
        } else if let exact {
            pick(exact)
        } else {
            pick(FoodChoice(label: clean, food: MealFood(name: clean), key: foodKey(clean)))
        }
    }
}

/// What a search for `query` offers, best first: the name that is exactly what
/// was typed; then the names found *inside* it, the longest first, so
/// "carottes" offers the carrot and "purée de carottes" offers it before
/// anything shorter; then the names that contain it.
private func matches(_ choices: [FoodChoice], _ query: String) -> [FoodChoice] {
    let keyed = choices.map { ($0, searchKey($0.label)) }
    let exact = keyed.filter { $0.1 == query }
    let inside = keyed
        .filter { $0.1.count >= minContained && $0.1 != query && query.contains($0.1) }
        .sorted { $0.1.count > $1.1.count }
    let containing = keyed.filter { $0.1.contains(query) }
    var seen = Set<String>()
    return (exact + inside + containing).map(\.0).filter { seen.insert($0.key).inserted }
}

/// One food on the meal: its name, a taste or a serving, and how it went.
private struct MealFoodRow: View {
    @Binding var food: MealFood
    var allergic = false
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
            // Said, not stopped: the parent may be logging the very meal that
            // showed it, or one the doctor asked for (BDR-21).
            if allergic { AllergyMarker() }
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
    var allergies: Set<String> = []
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
        allergies: Set<String> = [],
        onSave: @escaping (Feeding) -> Void,
        onDelete: (() -> Void)? = nil
    ) {
        self.initial = initial
        self.known = known
        self.allergies = allergies
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
                    MealFoodsEditor(foods: $foods, known: known, allergies: allergies)
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
