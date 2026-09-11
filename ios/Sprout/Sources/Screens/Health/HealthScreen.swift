import SproutData
import SwiftUI

/// The parent's own record, from `ui/health/HealthScreen.kt`.
///
/// Belongs to the parent rather than to a baby (BDR-0001): no `babyId`, and it
/// is the one table that never leaves the phone — not to a partner's handset,
/// not into a backup's sync payload. That is why `deleteWellbeing` is a real
/// delete and not the soft delete every other log gets: there is no merge for
/// the deletion to have to survive.
@Observable
@MainActor
final class HealthViewModel {
    var entries: [Wellbeing] = []
    var gaveBirth = false
    var breastfeeding = false
    var deliveryType: DeliveryType?

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeEverything() async {
        let repository = self.repository
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await observe(repository.wellbeing) { [weak self] in self?.entries = $0 }
            }
            group.addTask {
                await observe(repository.parentProfile) { [weak self] profile in
                    self?.gaveBirth = profile?.gaveBirth ?? false
                    self?.breastfeeding = profile?.breastfeeding ?? false
                    self?.deliveryType = profile?.deliveryType
                }
            }
        }
    }

    func add(_ entry: Wellbeing) { try? repository.addWellbeing(entry) }
    func delete(_ entry: Wellbeing) { try? repository.deleteWellbeing(entry) }
}

struct HealthScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: HealthViewModel?
    @State private var pendingDelete: Wellbeing?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_wellbeing"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? HealthViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func content(_ model: HealthViewModel) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                WellbeingAddCard(
                    gaveBirth: model.gaveBirth,
                    breastfeeding: model.breastfeeding,
                    deliveryType: model.deliveryType,
                    onAdd: { model.add($0) }
                )

                SectionLabel(Str.t("history"))
                if model.entries.isEmpty {
                    EmptyHint(Str.t("wellbeing_empty"))
                }
                ForEach(model.entries) { entry in
                    EntryCard(
                        title: Str.t("wellbeing_mood_title", moodEmoji(entry.mood), entry.mood),
                        subtitle: subtitle(entry),
                        meta: SproutDateStyle.dateTime(entry.time),
                        systemImage: "heart.fill",
                        onDelete: { pendingDelete = entry }
                    )
                }
            }
            .padding(Spacing.regular)
        }
        .sproutStyle()
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            if let entry = pendingDelete { model.delete(entry) }
            pendingDelete = nil
        }
    }

    private func subtitle(_ entry: Wellbeing) -> String {
        var parts: [String] = []
        if let recovery = entry.recovery { parts.append(Str.t("wellbeing_sub_healing", recovery.label)) }
        if let bleeding = entry.bleeding { parts.append(Str.t("wellbeing_sub_bleeding", bleeding.label)) }
        if let breast = entry.breast { parts.append(Str.t("wellbeing_sub_breasts", breast.label)) }
        if let notes = entry.notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty {
            parts.append(notes)
        }
        return parts.joined(separator: Str.t("feeding_detail_separator"))
    }
}

/// Today's entry, written in place rather than behind a "+".
///
/// The other logs get a sheet, this one does not, and the difference is
/// deliberate: a parent logs a nappy twenty times a day and their own state
/// perhaps once, so the cost worth cutting here is the tap that reminds you the
/// screen exists — not the space the form takes up.
private struct WellbeingAddCard: View {
    let gaveBirth: Bool
    let breastfeeding: Bool
    let deliveryType: DeliveryType?
    let onAdd: (Wellbeing) -> Void

    @State private var mood = 3
    @State private var bleeding: Bleeding?
    @State private var recovery: Recovery?
    @State private var breast: BreastState?
    @State private var time = Clock.millis
    @State private var notes = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            Text(Str.t("wellbeing_add_title"))
                .font(.headline)
                .foregroundStyle(SproutColor.onSurface)

            FieldLabel(Str.t("field_mood"))
            ChoiceChips(
                options: [1, 2, 3, 4, 5],
                selection: Binding(get: { mood }, set: { mood = $0 ?? mood }),
                label: { "\(moodEmoji($0)) \($0)" }
            )

            // Both questions are asked only of a parent who gave birth, and the
            // healing one is worded for the delivery — a caesarean question put
            // to someone who had a vaginal birth is the kind of small wrongness
            // this screen cannot afford.
            if gaveBirth {
                FieldLabel(healingFieldLabel(deliveryType))
                ChoiceChips(
                    options: Recovery.allCases,
                    selection: $recovery,
                    label: \.label,
                    allowsDeselection: true
                )

                FieldLabel(Str.t("field_bleeding"))
                ChoiceChips(
                    options: Bleeding.allCases,
                    selection: $bleeding,
                    label: \.label,
                    allowsDeselection: true
                )
            }

            if breastfeeding {
                FieldLabel(Str.t("field_breast_comfort"))
                ChoiceChips(
                    options: BreastState.allCases,
                    selection: $breast,
                    label: \.label,
                    allowsDeselection: true
                )
            }

            // Yesterday's rough night is worth recording today.
            DateTimeField(label: Str.t("field_date"), millis: $time)
            NotesField(text: $notes)

            HStack {
                Spacer()
                Button(Str.t("wellbeing_add"), action: save)
                    .buttonStyle(.borderedProminent)
            }
            .padding(.top, Spacing.hairline)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    private func save() {
        onAdd(
            Wellbeing(
                time: time,
                mood: mood,
                // A question that was not asked is stored as unanswered, not as
                // whatever was left selected before the profile changed.
                bleeding: gaveBirth ? bleeding : nil,
                recovery: gaveBirth ? recovery : nil,
                breast: breastfeeding ? breast : nil,
                notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        )
        mood = 3
        bleeding = nil
        recovery = nil
        breast = nil
        notes = ""
        time = Clock.millis
    }
}
