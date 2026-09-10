import SproutData
import SwiftUI
import UIKit

/// From `ui/settings/SettingsScreen.kt`.
///
/// Three sections differ from Android's, and each for a platform reason rather
/// than a design one:
///
/// - **The language list is gone.** `CFBundleLocalizations` in the Info.plist
///   gives every iOS app a per-app language picker in the system Settings, so
///   Sprout offers a row that opens it instead of shipping a second, competing
///   list that the system one would silently override. `AppLocale.kt` has no
///   counterpart here for the same reason.
/// - **The two reminder switches say what they are.** Neither the feeding
///   reminder nor the growth-spurt note has a scheduler on this side yet, and a
///   switch that promises a notification nobody will receive is worse than a
///   sentence admitting it.
/// - **Support is `UIApplication.open`** where Android hands over an
///   `ACTION_VIEW` intent. Same act: the URL leaves for the browser and Sprout
///   fetches nothing (BDR-11).
struct SettingsScreen: View {
    @Environment(\.sprout) private var sprout
    @Environment(\.openURL) private var openURL
    @State private var profile: ParentProfile?
    @State private var noBrowser = false

    // Read once and held, because the store behind them is not observable — a
    // plain key-value store, deliberately (ADR-0018). The screen is the only
    // thing that writes them, so it is also the only thing that has to know.
    @State private var feedingReminders = false
    @State private var feedingInterval = FeedingReminderSettings.defaultIntervalMinutes
    @State private var growthSpurtAlerts = false
    /// Shown when iOS has been asked and said no. There is nothing to do about
    /// it from here — only Settings can change it — so the switch goes back off
    /// rather than sitting on while nothing arrives.
    @State private var notificationsRefused = false

    var body: some View {
        Form {
            languageSection

            remindersSection

            if let profile {
                checkInSection(profile)
                questionsSection(profile)
            }

            sharingSection
            supportSection
        }
        .navigationTitle(Str.t("screen_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(Str.t("settings_support_no_browser"), isPresented: $noBrowser) {
            Button(Str.t("action_close"), role: .cancel) {}
        }
        .onAppear {
            feedingReminders = FeedingReminderSettings.isEnabled(sprout.settingsStore)
            feedingInterval = FeedingReminderSettings.intervalMinutes(sprout.settingsStore)
            growthSpurtAlerts = GrowthSpurtSettings.isEnabled(sprout.settingsStore)
        }
        .task {
            await observe(sprout.repository.parentProfile) { profile = $0 }
        }
    }

    // MARK: - Language

    private var languageSection: some View {
        Section(Str.t("settings_language")) {
            Button {
                // The system's own per-app picker. Sprout does not keep a second
                // list: iOS applies its choice over anything the app decides, so
                // two lists would disagree and the app's would lose.
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url)
                }
            } label: {
                HStack {
                    Image(systemName: "globe")
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                        .frame(width: 30)
                    Text(Str.t("settings_language_system"))
                        .foregroundStyle(SproutColor.onSurface)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "arrow.up.forward.app")
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
            }
        }
    }

    // MARK: - The parent's own tracking

    /// Master switch for tracking one's own wellbeing. Off means the dashboard
    /// stops offering the check-in and drops its shortcut — **nothing is
    /// deleted**, and the history stays one tap away under the heart.
    private func checkInSection(_ profile: ParentProfile) -> some View {
        Section {
            Toggle(isOn: Binding(
                get: { profile.trackWellbeing },
                set: { try? sprout.repository.setTrackWellbeing($0) }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Str.t("settings_checkin_section"))
                    Text(Str.t("settings_checkin_track_desc"))
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
            }
        }
    }

    /// One switch per body question, listing only the ones this parent's
    /// capabilities surface (BDR-0001) — never a list of every question Sprout
    /// knows how to ask.
    @ViewBuilder
    private func questionsSection(_ profile: ParentProfile) -> some View {
        if profile.trackWellbeing && (profile.gaveBirth || profile.breastfeeding) {
            Section {
                if profile.gaveBirth {
                    QuestionToggle(
                        title: Str.t("settings_healing_question"),
                        question: healingQuestion(profile.deliveryType),
                        isOn: Binding(
                            get: { profile.askHealing },
                            set: { try? sprout.repository.setAskHealing($0) }
                        )
                    )
                    QuestionToggle(
                        title: Str.t("settings_bleeding_question"),
                        question: Str.t("checkin_q_bleeding"),
                        isOn: Binding(
                            get: { profile.askBleeding },
                            set: { try? sprout.repository.setAskBleeding($0) }
                        )
                    )
                }
                if profile.breastfeeding {
                    QuestionToggle(
                        title: Str.t("settings_breasts_question"),
                        question: Str.t("checkin_q_breasts"),
                        isOn: Binding(
                            get: { profile.askBreasts },
                            set: { try? sprout.repository.setAskBreasts($0) }
                        )
                    )
                }
            } header: {
                Text(Str.t("settings_checkin_questions"))
            } footer: {
                Text(Str.t("settings_checkin_section_desc"))
            }
        }
    }

    // MARK: - Reminders

    /// The three reminder settings (ADR-0019).
    ///
    /// Turning one on is the *only* moment Sprout asks iOS for permission to
    /// notify. A phone whose owner wants no reminders is never asked at all.
    private var remindersSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { feedingReminders },
                set: { setFeedingReminders($0) }
            )) {
                settingLabel(
                    Str.t("settings_feeding_reminders"),
                    Str.t("settings_feeding_reminders_desc")
                )
            }

            if feedingReminders {
                Picker(Str.t("settings_feeding_interval_label"), selection: Binding(
                    get: { feedingInterval },
                    set: { setFeedingInterval($0) }
                )) {
                    ForEach(FeedingReminderSettings.intervalChoices, id: \.self) { minutes in
                        Text(SproutFormat.duration(millis: Int64(minutes) * 60_000).text)
                            .tag(minutes)
                    }
                }
            }

            Toggle(isOn: Binding(
                get: { growthSpurtAlerts },
                set: { setGrowthSpurtAlerts($0) }
            )) {
                settingLabel(
                    Str.t("settings_growth_spurts"),
                    Str.t("settings_growth_spurts_desc")
                )
            }
        } footer: {
            if notificationsRefused {
                // Untranslated for the reason `NotYetOnIOS` gives: the catalog is
                // generated from Android's resources and CI checks the two match,
                // and Android has no such line because it never needs one — its
                // permission is granted at install time or asked once by the OS.
                Text("Notifications are off for Sprout in iOS Settings.")
            }
        }
    }

    @ViewBuilder
    private func settingLabel(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(detail)
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
        }
    }

    private func setFeedingReminders(_ on: Bool) {
        // Optimistic, then corrected: the switch moves under the finger and goes
        // back if iOS refuses, rather than waiting on a prompt to animate.
        feedingReminders = on
        Task { await apply(on) { FeedingReminderSettings.setEnabled($0, in: sprout.settingsStore) } }
    }

    private func setGrowthSpurtAlerts(_ on: Bool) {
        growthSpurtAlerts = on
        Task { await apply(on) { GrowthSpurtSettings.setEnabled($0, in: sprout.settingsStore) } }
    }

    private func setFeedingInterval(_ minutes: Int) {
        feedingInterval = minutes
        FeedingReminderSettings.setIntervalMinutes(minutes, in: sprout.settingsStore)
        Task { await rebuildSchedule() }
    }

    /// Stores a switch, asking iOS for permission first when it is going on.
    private func apply(_ on: Bool, _ store: @escaping (Bool) -> Void) async {
        if on {
            // Spelled out rather than `await !f()`: Swift will not have `await`
            // sitting to the right of an operator, and the negated form is the
            // one that reads.
            let granted = await ReminderScheduler.requestAuthorization()
            if !granted {
                notificationsRefused = true
                // Back to whatever is actually stored, so the switch cannot sit
                // on while iOS drops everything it would send.
                feedingReminders = FeedingReminderSettings.isEnabled(sprout.settingsStore)
                growthSpurtAlerts = GrowthSpurtSettings.isEnabled(sprout.settingsStore)
                return
            }
        }
        notificationsRefused = false
        store(on)
        await rebuildSchedule()
    }

    private func rebuildSchedule() async {
        await ReminderScheduler.rebuild(
            repository: sprout.repository,
            settings: sprout.settingsStore
        )
    }

    // MARK: - Sharing

    private var sharingSection: some View {
        Section(Str.t("settings_sharing")) {
            NavigationLink(value: LogDestination.sync) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Str.t("screen_sync"))
                        // On the `Text`, not on the `NavigationLink`, and not on
                        // a combined element either — two screenshot runs proved
                        // that a link's own identifier does not reach the
                        // accessibility tree from inside a `Form`. A `Text` maps
                        // straight to a static-text element that SwiftUI does not
                        // restructure, and tapping one taps the row it sits in.
                        .accessibilityIdentifier("settings-sync")
                    Text(Str.t("settings_sync_hint"))
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
            }
        }
    }

    // MARK: - Support

    /// The one place Sprout asks for anything (BDR-11). It sits last, it appears
    /// nowhere else, and both rows only hand a URL to the browser — no purchase,
    /// and nothing bought: every feature is here either way.
    private var supportSection: some View {
        Section {
            SupportRow(
                label: Str.t("settings_support_sponsors"),
                hint: Str.t("settings_support_sponsors_hint")
            ) { open(SupportLinks.githubSponsors) }

            SupportRow(
                label: Str.t("settings_support_coffee"),
                hint: Str.t("settings_support_coffee_hint")
            ) { open(SupportLinks.buyMeACoffee) }
        } header: {
            Text(Str.t("settings_support"))
        } footer: {
            Text(Str.t("settings_support_desc"))
        }
    }

    /// Through SwiftUI's `openURL` rather than `UIApplication.open`, and with its
    /// completion: a phone with nothing registered for `https` — a locked-down
    /// device genuinely can be one — says so instead of appearing to do nothing.
    private func open(_ link: String) {
        guard let url = SupportLinks.url(link) else { noBrowser = true; return }
        openURL(url) { accepted in
            if !accepted { noBrowser = true }
        }
    }
}

/// A row that leaves the app: the globe says so before the tap does.
private struct SupportRow: View {
    let label: String
    let hint: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).foregroundStyle(SproutColor.onSurface)
                    Text(hint)
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                // A globe, not the chevron the in-app rows use: this one leaves
                // Sprout.
                Image(systemName: "globe")
                    .foregroundStyle(SproutColor.onSurfaceVariant)
                    .accessibilityLabel(Str.t("cd_opens_in_browser"))
            }
        }
    }
}

private struct QuestionToggle: View {
    let title: String
    let question: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(question)
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
        }
    }
}

