import SproutData
import SwiftUI

/// The daily check-in, from `ui/checkin/DailyCheckInScreen.kt`.
///
/// One question at a time, on purpose. The whole set on one page is a form, and
/// a form about how your body is healing at six days postpartum reads as an
/// assessment; a single question with a Back and a Next reads as being asked.
@Observable
@MainActor
final class CheckInViewModel {
    var profile: ParentProfile?

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeProfile() async {
        await observe(repository.parentProfile) { [weak self] in self?.profile = $0 }
    }

    /// Save today's check-in; the dashboard stops offering it until tomorrow.
    ///
    /// The two writes are one act — an entry saved without the timestamp leaves
    /// the card up, and a timestamp without the entry loses the answers. Nothing
    /// here is cancellable, unlike Android where saving also pops the screen and
    /// takes the ViewModel's scope with it.
    func submit(_ entry: Wellbeing) {
        try? repository.addWellbeing(entry)
        try? repository.updateParentLastCheckIn(Clock.millis)
    }

    /// Stop asking `question`; the flow moves on without it.
    func optOut(of question: CheckInQuestion) {
        switch question {
        case .healing: try? repository.setAskHealing(false)
        case .bleeding: try? repository.setAskBleeding(false)
        case .breasts: try? repository.setAskBreasts(false)
        // Mood and notes are the check-in; there is nothing left without them.
        case .mood, .notes: break
        }
    }
}

struct DailyCheckInScreen: View {
    @Environment(\.sprout) private var sprout
    @Environment(\.dismiss) private var dismiss
    @State private var model: CheckInViewModel?

    var body: some View {
        Group {
            if let model, let profile = model.profile {
                CheckInFlow(
                    profile: profile,
                    onSubmit: { model.submit($0); dismiss() },
                    // "Not now" just closes it. The card stays on the dashboard,
                    // because coming back later is the entire point.
                    onSkip: { dismiss() },
                    onOptOut: { model.optOut(of: $0) }
                )
            } else {
                Color.clear
            }
        }
        .navigationTitle(Str.t("home_checkin_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? CheckInViewModel(repository: sprout.repository)
            self.model = model
            await model.observeProfile()
        }
    }
}

private struct CheckInFlow: View {
    let profile: ParentProfile
    let onSubmit: (Wellbeing) -> Void
    let onSkip: () -> Void
    let onOptOut: (CheckInQuestion) -> Void

    /// Step 0 is the greeting; 1…n are the questions.
    @State private var step = 0
    @State private var mood = 3
    @State private var recovery: Recovery?
    @State private var bleeding: Bleeding?
    @State private var breast: BreastState?
    @State private var notes = ""
    /// Fixed when the flow opens. A check-in answered across midnight belongs to
    /// the evening it was started in, not to the two days it touched.
    @State private var now = Clock.millis

    private var questions: [CheckInQuestion] {
        checkInQuestions(
            gaveBirth: profile.gaveBirth,
            breastfeeding: profile.breastfeeding,
            askHealing: profile.askHealing,
            askBleeding: profile.askBleeding,
            askBreasts: profile.askBreasts
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.regular) {
                HStack {
                    Spacer()
                    Button(Str.t("checkin_skip"), action: onSkip)
                        .font(.callout)
                }

                if step == 0 {
                    intro
                } else {
                    questionPage
                }
            }
            .padding(Spacing.section)
        }
        .sproutStyle()
    }

    /// "Good evening, Alex 🌸". The flower is not in the catalog: it is the same
    /// in every language, and a translator asked to render an emoji is a
    /// translator given a chance to lose it.
    private var greeting: String {
        let words = Str.t("checkin_greeting", SproutFormat.greeting(at: now).text, profile.name)
        return words + " 🌸"
    }

    private var intro: some View {
        VStack(spacing: Spacing.snug) {
            Text(greeting)
                .font(.title.weight(.bold))
                .multilineTextAlignment(.center)
                .foregroundStyle(SproutColor.onSurface)
            Text(Str.t("checkin_intro_body"))
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            Button(Str.t("checkin_begin")) { step = 1 }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .padding(.top, Spacing.regular)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, Spacing.section)
    }

    @ViewBuilder
    private var questionPage: some View {
        // Opting out shortens the list, so the same step index lands on the next
        // question and nothing else moves. Clamped because the last question can
        // be the one that disappears.
        let questions = self.questions
        let index = min(step, questions.count) - 1
        let question = questions[max(0, index)]
        let isLast = step >= questions.count

        VStack(alignment: .leading, spacing: Spacing.snug) {
            ProgressView(value: Double(min(step, questions.count)), total: Double(questions.count))
            Text(Str.t("checkin_progress", min(step, questions.count), questions.count))
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)

            Text(title(of: question))
                .font(.title2.weight(.bold))
                .foregroundStyle(SproutColor.onSurface)
                .padding(.top, Spacing.tight)

            answer(to: question)

            if question.isOptional {
                Button(Str.t("checkin_opt_out")) {
                    // The answer goes with the question. A value picked just
                    // before opting out must not be saved silently.
                    switch question {
                    case .healing: recovery = nil
                    case .bleeding: bleeding = nil
                    case .breasts: breast = nil
                    default: break
                    }
                    onOptOut(question)
                }
                .font(.callout)
                .padding(.top, Spacing.tight)
            }

            HStack(spacing: Spacing.snug) {
                Button(Str.t("action_back")) { step = max(0, step - 1) }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button(isLast ? Str.t("checkin_save") : Str.t("action_next")) {
                    if isLast { save() } else { step += 1 }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            }
            .padding(.top, Spacing.section)
        }
    }

    @ViewBuilder
    private func answer(to question: CheckInQuestion) -> some View {
        switch question {
        case .mood:
            ChoiceChips(
                options: [1, 2, 3, 4, 5],
                selection: Binding(get: { mood }, set: { mood = $0 ?? mood }),
                label: { "\(moodEmoji($0)) \($0)" }
            )
        case .healing:
            ChoiceChips(
                options: Recovery.allCases,
                selection: $recovery,
                label: \.label,
                allowsDeselection: true
            )
        case .bleeding:
            ChoiceChips(
                options: Bleeding.allCases,
                selection: $bleeding,
                label: \.label,
                allowsDeselection: true
            )
        case .breasts:
            ChoiceChips(
                options: BreastState.allCases,
                selection: $breast,
                label: \.label,
                allowsDeselection: true
            )
        case .notes:
            NotesField(text: $notes)
        }
    }

    private func title(of question: CheckInQuestion) -> String {
        switch question {
        case .mood: return Str.t("checkin_q_mood")
        case .healing: return healingQuestion(profile.deliveryType)
        case .bleeding: return Str.t("checkin_q_bleeding")
        case .breasts: return Str.t("checkin_q_breasts")
        case .notes: return Str.t("checkin_q_notes")
        }
    }

    private func save() {
        onSubmit(
            Wellbeing(
                time: now,
                mood: mood,
                bleeding: bleeding,
                recovery: recovery,
                breast: breast,
                notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        )
    }
}
