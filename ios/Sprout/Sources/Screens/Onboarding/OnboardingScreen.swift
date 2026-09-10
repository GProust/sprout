import SproutData
import SwiftUI

/// The first five minutes. From `ui/onboarding/OnboardingScreen.kt`.
///
/// Four steps, and each one earns its place: the welcome says what Sprout is and
/// what it does not do, the parent's own answers decide which questions the
/// wellbeing entries will offer (BDR-0001), the baby is what everything else
/// hangs off, and the care questions are asked *after* the baby has a name so
/// they can use it.
///
/// Nothing is written until the last step. A parent who backs out at step three
/// has left nothing behind, which is what makes "you can change all of this
/// later" true rather than reassuring.
struct OnboardingScreen: View {

    /// Everything the flow collected, handed over in one piece.
    struct Answers {
        var name = ""
        var babyName = ""
        var birthDate = Clock.millis
        var gaveBirth = false
        var breastfeeding = false
        var deliveryType: DeliveryType? = nil
        var trackWellbeing = true
    }

    let onFinish: (Answers) -> Void

    @State private var step = 0
    @State private var answers = Answers()

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.regular) {
                Spacer().frame(height: Spacing.section)
                switch step {
                case 0: welcome
                case 1: aboutYou
                case 2: baby
                default: care
                }
            }
            .frame(maxWidth: .infinity)
            .padding(Spacing.loose)
        }
        .sproutStyle()
        .accessibilityIdentifier("onboarding")
    }

    // MARK: - The steps

    private var welcome: some View {
        VStack(spacing: Spacing.snug) {
            Text("🌱").font(.system(size: 64))
            Text(Str.t("onboarding_welcome_title"))
                .font(.title.weight(.bold))
                .multilineTextAlignment(.center)
            Text(Str.t("onboarding_welcome_body"))
                .font(.body)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Button(Str.t("onboarding_get_started")) { step = 1 }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .padding(.top, Spacing.loose)
                .accessibilityIdentifier("onboarding-start")
        }
    }

    private var aboutYou: some View {
        VStack(alignment: .leading, spacing: Spacing.snug) {
            heading(Str.t("onboarding_about_title"), Str.t("onboarding_about_subtitle"))

            TextField(Str.t("onboarding_your_name"), text: $answers.name)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.words)

            // Asked here, with the name, because both are about the parent
            // rather than the baby — and because a parent who doesn't want the
            // check-in should be able to say so before Sprout starts offering
            // it, not after.
            ToggleRow(
                label: Str.t("onboarding_checkin_q"),
                help: Str.t("onboarding_checkin_help"),
                isOn: $answers.trackWellbeing
            )

            stepButtons(
                back: { step = 0 },
                nextLabel: Str.t("action_next"),
                // The one required answer in the whole flow: the check-in and
                // the report both address the parent by name.
                nextEnabled: !answers.name.trimmingCharacters(in: .whitespaces).isEmpty,
                next: { step = 2 }
            )
        }
    }

    private var baby: some View {
        VStack(alignment: .leading, spacing: Spacing.snug) {
            heading(Str.t("onboarding_baby_title"), Str.t("onboarding_baby_subtitle"))

            TextField(Str.t("field_baby_name"), text: $answers.babyName)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.words)

            FieldLabel(Str.t("field_date_of_birth"))
            DateField(label: Str.t("picker_born"), millis: $answers.birthDate)

            stepButtons(
                back: { step = 1 },
                nextLabel: Str.t("action_next"),
                // Not required: a baby who has not arrived yet, or has not been
                // named yet, is a real case. The profile is created without one
                // and the dashboard asks again.
                nextEnabled: true,
                next: { step = 3 }
            )
        }
    }

    private var care: some View {
        let named = answers.babyName.trimmingCharacters(in: .whitespaces)
        // "your baby" when they have not been named yet, so the questions below
        // read as sentences either way.
        let who = named.isEmpty ? Str.t("onboarding_default_baby") : named

        return VStack(alignment: .leading, spacing: Spacing.snug) {
            // Without the check-in these answers still have a job: they decide
            // which body questions the wellbeing entries offer. Say that instead
            // of promising a daily check-in the parent just turned down.
            heading(
                Str.t("onboarding_care_title", who),
                answers.trackWellbeing
                    ? Str.t("onboarding_care_subtitle")
                    : Str.t("onboarding_care_subtitle_no_checkin")
            )

            ToggleRow(
                label: Str.t("onboarding_gave_birth_q", who),
                help: Str.t("onboarding_gave_birth_help"),
                isOn: $answers.gaveBirth
            )

            if answers.gaveBirth {
                FieldLabel(Str.t("onboarding_birth_how"))
                ChoiceChips(
                    options: DeliveryType.allCases,
                    selection: $answers.deliveryType,
                    label: \.label,
                    allowsDeselection: true
                )
            }

            ToggleRow(
                label: Str.t("onboarding_breastfeeding_q", who),
                help: Str.t("onboarding_breastfeeding_help"),
                isOn: $answers.breastfeeding
            )

            stepButtons(
                back: { step = 2 },
                nextLabel: Str.t("onboarding_all_done"),
                nextEnabled: true,
                next: {
                    var finished = answers
                    // The delivery question was only asked of a parent who gave
                    // birth; keeping an answer they never gave would put it in
                    // the wellbeing questions and the report.
                    if !finished.gaveBirth { finished.deliveryType = nil }
                    onFinish(finished)
                },
                nextIdentifier: "onboarding-finish"
            )
        }
    }

    // MARK: - Pieces

    @ViewBuilder
    private func heading(_ title: String, _ subtitle: String) -> some View {
        VStack(spacing: Spacing.tight) {
            Text(title)
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            Text(subtitle)
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, Spacing.tight)
    }

    @ViewBuilder
    private func stepButtons(
        back: @escaping () -> Void,
        nextLabel: String,
        nextEnabled: Bool,
        next: @escaping () -> Void,
        nextIdentifier: String? = nil
    ) -> some View {
        HStack(spacing: Spacing.snug) {
            Button(Str.t("action_back"), action: back)
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            Button(nextLabel, action: next)
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .disabled(!nextEnabled)
                .accessibilityIdentifier(nextIdentifier ?? "onboarding-next")
        }
        .padding(.top, Spacing.loose)
    }
}

/// A switch with the question above it and the reason underneath.
///
/// The help line is not decoration: every one of these decides what Sprout will
/// ask later, and a parent answering at 3 a.m. deserves to know which.
private struct ToggleRow: View {
    let label: String
    let help: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.body)
                Text(help)
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
        }
        .padding(.vertical, Spacing.hairline)
    }
}

/// Writes what onboarding collected. From `StartupViewModel.completeOnboarding`.
///
/// **The baby first, then the profile.** `addBaby` makes the first baby active
/// only when a profile already exists, and at this point one does not — so the
/// selection is set here, explicitly, by saving the profile with the new baby's
/// id. Doing it the other way round leaves a phone with a baby and nothing
/// selected, which opens on an empty dashboard.
///
/// A blank baby name writes no baby at all rather than one called "". The
/// dashboard then asks again, which is the right prompt for a parent who set
/// Sprout up before the birth.
func completeOnboarding(
    _ answers: OnboardingScreen.Answers,
    into repository: SproutRepository
) throws {
    let babyName = answers.babyName.trimmingCharacters(in: .whitespaces)
    let babyId = babyName.isEmpty
        ? nil
        : try repository.addBaby(name: babyName, birthDate: answers.birthDate)

    try repository.saveParentProfile(
        ParentProfile(
            name: answers.name.trimmingCharacters(in: .whitespaces),
            gaveBirth: answers.gaveBirth,
            breastfeeding: answers.breastfeeding,
            deliveryType: answers.deliveryType,
            trackWellbeing: answers.trackWellbeing,
            lastCheckIn: nil,
            activeBabyId: babyId
        )
    )
}
