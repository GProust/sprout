import SproutData
import SwiftUI

/// The foods a baby has been offered, one at a time (BDR-20). From
/// `ui/foods/FoodsScreen.kt`.
///
/// There is no food log behind this page: a solid meal lists the foods that were
/// in it, and everything here is read back from those meals. Logging from here
/// writes an ordinary solid meal, exactly as the feeding screen does.
@Observable
@MainActor
final class FoodsViewModel {
    /// The active baby's feeds, so a food's meal can be opened to correct.
    var feedings: [Feeding] = []
    /// Every meal in the household that named a food — what the form offers
    /// back.
    var household: [Feeding] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    /// Both streams at once, in one task group — two `.task` modifiers would
    /// start in an order SwiftUI does not promise.
    func observeEverything() async {
        let repository = self.repository
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await observe(repository.feedings) { [weak self] in self?.feedings = $0 }
            }
            group.addTask {
                await observe(repository.householdMealsWithFoods) { [weak self] in self?.household = $0 }
            }
        }
    }

    /// The active baby's foods, most recently offered first.
    var foods: [FoodHistory] { foodHistories(feedings) }

    /// The household's foods, for the meal form's suggestions.
    var known: [FoodHistory] { foodHistories(household) }

    func meal(uid: String) -> Feeding? { feedings.first { $0.uid == uid } }

    /// A meal is a feed, saved the way the feeding screen saves one: an edit
    /// keeps its id and its uid, and is the same row on both phones.
    func save(_ meal: Feeding) { try? repository.addFeeding(meal) }

    func delete(_ meal: Feeding) { try? repository.deleteFeeding(meal) }
}

struct FoodsScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: FoodsViewModel?
    @State private var adding = false
    @State private var editing: Feeding?
    /// The two orders a parent can read the list in — neither of them by how
    /// well a food is going.
    @State private var byName = false

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_foods"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? FoodsViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func content(_ model: FoodsViewModel) -> some View {
        let foods = ordered(model.foods)
        let now = Clock.millis
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    if foods.isEmpty {
                        EmptyHint(Str.t("foods_empty"))
                    } else {
                        Text(Str.t("foods_count", foods.count))
                            .font(.headline)
                            .foregroundStyle(SproutColor.onSurface)
                        ChoiceChips(
                            options: [false, true],
                            selection: Binding(get: { byName }, set: { byName = $0 ?? byName }),
                            label: { $0 ? Str.t("foods_sort_name") : Str.t("foods_sort_recent") }
                        )
                        ForEach(foods) { food in
                            FoodCard(food: food, now: now) { uid in editing = model.meal(uid: uid) }
                        }
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("foods_log_meal")) { adding = true }
                // A handle for the screenshot run that is not the visible label,
                // which changes with the language.
                .accessibilityIdentifier("foods-add")
                .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(isPresented: $adding) {
            MealForm(initial: nil, known: model.known) { model.save($0); adding = false }
        }
        .sheet(item: $editing) { meal in
            MealForm(
                initial: meal,
                known: model.known,
                onSave: { model.save($0); editing = nil },
                onDelete: { model.delete(meal); editing = nil }
            )
        }
    }

    private func ordered(_ foods: [FoodHistory]) -> [FoodHistory] {
        guard byName else { return foods }
        return foods.sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }
    }
}

/// One food: how many meals it was in, tastes and servings apart, the first and
/// the last time, and how it went when that was noted. Its meals are behind
/// *Details*, each one opening the meal itself.
///
/// No colour means anything here and nothing is ranked: a count is a fact about
/// the meals that were logged, never progress toward a number Sprout chose.
private struct FoodCard: View {
    let food: FoodHistory
    let now: Int64
    let onOpenMeal: (String) -> Void

    @State private var expanded = false

    private var separator: String { Str.t("feeding_detail_separator") }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.hairline) {
            HStack(alignment: .firstTextBaseline) {
                Text(food.displayName)
                    .font(.body.weight(.medium))
                    .foregroundStyle(SproutColor.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(Str.t("food_times_offered", food.timesOffered))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(SproutColor.primary)
            }
            Text(portionsLine)
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            Text(datesLine)
                .font(.footnote)
                .foregroundStyle(SproutColor.onSurfaceVariant)

            let noted = FoodReception.allCases.filter { food.timesReceived($0) > 0 }
            if !noted.isEmpty {
                HStack(spacing: Spacing.regular) {
                    ForEach(noted, id: \.self) { reception in
                        let count = food.timesReceived(reception)
                        // The symbol and the number are drawn; the sentence is
                        // what VoiceOver reads, so it hears "Refused: 2" rather
                        // than "thumbs down, 2".
                        HStack(spacing: Spacing.hairline) {
                            Image(systemName: reception.systemImage)
                            Text("\(count)")
                        }
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Str.t("food_reception_count", reception.label, count))
                    }
                }
                .padding(.top, Spacing.hairline)
            }

            Button {
                withAnimation(.snappy) { expanded.toggle() }
            } label: {
                HStack(spacing: Spacing.hairline) {
                    Text(Str.t("action_details"))
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                }
                .font(.footnote)
            }
            .buttonStyle(.plain)
            .foregroundStyle(SproutColor.primary)
            .padding(.top, Spacing.hairline)

            if expanded {
                ForEach(food.offers, id: \.feedUid) { offer in
                    Button { onOpenMeal(offer.feedUid) } label: {
                        Text(offerLine(offer))
                            .font(.footnote)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, Spacing.hairline)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(Spacing.regular)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    /// "3 tastes · 2 servings" — whichever of the two there are.
    private var portionsLine: String {
        var parts: [String] = []
        if food.tastes > 0 { parts.append(Str.t("food_tastes", food.tastes)) }
        if food.servings > 0 { parts.append(Str.t("food_servings", food.servings)) }
        return parts.joined(separator: separator)
    }

    /// "First: 3 Sep · Last: Today", or only the first when there has been one.
    private var datesLine: String {
        let first = Str.t("food_first", SproutFormat.dayLabel(food.firstOffered, now: now).text)
        guard food.timesOffered > 1 else { return first }
        return first + separator + Str.t("food_last", SproutFormat.dayLabel(food.lastOffered, now: now).text)
    }

    /// "Yesterday 12:30 · A serving · Liked it" for one meal the food was in.
    private func offerLine(_ offer: FoodOffer) -> String {
        var parts = [
            "\(SproutFormat.dayLabel(offer.time, now: now).text) \(SproutDateStyle.time(offer.time))",
            offer.portion.label,
        ]
        if let reception = offer.reception { parts.append(reception.label) }
        return parts.joined(separator: separator)
    }
}
