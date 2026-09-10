import SproutData
import SwiftUI

/// The household dashboard, from `ui/home/HomeScreen.kt`.
///
/// It shows **every tracked baby**, not whichever one is active. The thing being
/// done on this screen is logging, and with more than one baby a global "current
/// child" set in a menu is how a feed ends up on the wrong one (BDR-0009). With a
/// single baby there is nothing to disambiguate, so that one card opens out in
/// place — the same screen, composed for the family it belongs to.
@Observable
@MainActor
final class HomeViewModel {
    var parentName: String?
    var babies: [BabySummary] = []
    var hasProfile = false

    /// Coarse enough for "2 h ago". The screen re-reads it on a timer rather
    /// than per frame; nothing here needs a second hand.
    var now: Int64 = Clock.millis

    private let repository: SproutRepository

    /// How far back a "last fed" can be found. It only bounds the read, so a few
    /// hours of drift over a long-lived process changes nothing anyone sees.
    private let windowStart: Int64 = Clock.millis - 3 * 24 * 60 * 60 * 1000

    init(repository: SproutRepository) {
        self.repository = repository
    }

    /// Six observations feeding one summary.
    ///
    /// Each keeps the last value it saw, so a change to any one of them
    /// recomputes against the rest rather than blanking the screen — the
    /// equivalent of Compose's `combine` over six flows.
    ///
    /// The repository and the window are lifted out of `self` first: the child
    /// tasks are not main-actor isolated, and reading a stored property off a
    /// `@MainActor` view model from inside one would not be allowed. The
    /// assignments still happen on the main actor, because `observe` is.
    func observeEverything() async {
        let repository = self.repository
        let windowStart = self.windowStart

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await observe(repository.parentProfile) { [weak self] profile in
                    self?.parentName = profile?.name
                }
            }
            group.addTask {
                await observe(repository.babies) { [weak self] value in
                    self?.latestBabies = value
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.householdFeedings(since: windowStart)) { [weak self] value in
                    self?.latestFeedings = value
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.householdSleeps(since: windowStart)) { [weak self] value in
                    self?.latestSleeps = value
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.householdDiapers(since: windowStart)) { [weak self] value in
                    self?.latestDiapers = value
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.ongoingSleeps) { [weak self] value in
                    self?.latestOngoing = value
                    self?.recompute()
                }
            }
        }
    }

    private var latestBabies: [Baby] = []
    private var latestFeedings: [Feeding] = []
    private var latestSleeps: [Sleep] = []
    private var latestDiapers: [Diaper] = []
    private var latestOngoing: [Sleep] = []

    private func recompute() {
        hasProfile = !latestBabies.isEmpty
        babies = summariseHousehold(
            babies: latestBabies,
            feedings: latestFeedings,
            sleeps: latestSleeps,
            diapers: latestDiapers,
            ongoingSleeps: latestOngoing,
            dayStart: SproutFormat.startOfDay(now),
            now: now
        )
    }

    func tick() {
        now = Clock.millis
        recompute()
    }

    /// Close a sleep that was logged as still running.
    func wakeUp(_ sleep: Sleep) {
        var updated = sleep
        updated.endTime = Clock.millis
        try? repository.updateSleep(updated)
    }

    /// Selecting a baby before acting is deliberate, not incidental: every write
    /// resolves the active baby as it inserts, so a card's button has to know
    /// the write will land on the baby that was tapped.
    func select(_ baby: Baby) {
        guard let id = baby.id else { return }
        try? repository.setActiveBaby(id: id)
    }
}

struct HomeScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: HomeViewModel?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("app_name"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? HomeViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func content(_ model: HomeViewModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.regular) {
                if !model.hasProfile {
                    NoBabyYet()
                } else {
                    if let parent = model.parentName, !parent.isEmpty {
                        Text(
                            Str.t(
                                "home_greeting",
                                SproutFormat.greeting(at: model.now).text,
                                parent
                            )
                        )
                        .font(.headline)
                        .foregroundStyle(SproutColor.primary)
                    }

                    // A baby who is asleep right now is the one thing on this
                    // screen that changes without anyone touching it.
                    if let sleeping = model.babies.first(where: { $0.ongoingSleep != nil }),
                       let sleep = sleeping.ongoingSleep {
                        LiveSleepRow(name: sleeping.baby.name, sleep: sleep, now: model.now) {
                            model.wakeUp(sleep)
                        }
                    }

                    ForEach(model.babies) { summary in
                        BabyCard(summary: summary, now: model.now)
                    }
                }
            }
            .padding(Spacing.regular)
        }
        .sproutStyle()
        // A minute is as fine as "2 h ago" needs; anything shorter is spending
        // battery to redraw the same words.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                model.tick()
            }
        }
    }
}

/// One baby's figures for today, and when each thing last happened.
private struct BabyCard: View {
    let summary: BabySummary
    let now: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.snug) {
            Text(summary.baby.name)
                .font(.title2.weight(.bold))
                .foregroundStyle(SproutColor.onSurface)
            Text(SproutFormat.age(birthDate: summary.baby.birthDate, now: now).text)
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)

            SectionLabel(Str.t("home_today"))

            HStack(spacing: Spacing.snug) {
                StatCard(
                    label: Str.t("stat_feeds"),
                    value: "\(summary.feedsToday)",
                    systemImage: "drop.fill"
                )
                StatCard(
                    label: Str.t("stat_sleep"),
                    value: SproutFormat.duration(millis: summary.sleepTodayMs).text,
                    systemImage: "moon.zzz.fill"
                )
                StatCard(
                    label: Str.t("stat_diapers"),
                    value: "\(summary.diapersToday)",
                    systemImage: "figure.child"
                )
            }

            // "Fed 20 min ago · Slept 1 h ago · Nappy 40 min ago" — only the
            // ones that have ever happened.
            FlowLayout(spacing: Spacing.tight) {
                if let fed = summary.lastFeed {
                    Chip(text: Str.t("home_chip_fed", SproutFormat.relative(fed, now: now).text))
                }
                if let slept = summary.lastSleep {
                    Chip(text: Str.t("home_chip_slept", SproutFormat.relative(slept, now: now).text))
                }
                if let nappy = summary.lastDiaper {
                    Chip(text: Str.t("home_chip_nappy", SproutFormat.relative(nappy, now: now).text))
                }
                if let side = summary.nextSide {
                    Chip(text: side.label, emphasised: true)
                }
            }
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

private struct Chip: View {
    let text: String
    var emphasised = false

    var body: some View {
        Text(text)
            .font(.footnote)
            .padding(.horizontal, Spacing.snug)
            .padding(.vertical, Spacing.hairline + 2)
            .background(
                emphasised ? SproutColor.primaryContainer : SproutColor.background,
                in: Capsule()
            )
            .foregroundStyle(
                emphasised ? SproutColor.onPrimaryContainer : SproutColor.onSurfaceVariant
            )
    }
}

/// "Robin is asleep — 40 min", with the one tap that ends it.
private struct LiveSleepRow: View {
    let name: String
    let sleep: Sleep
    let now: Int64
    let onWake: () -> Void

    var body: some View {
        HStack {
            Image(systemName: "moon.zzz.fill")
                .foregroundStyle(SproutColor.onPrimaryContainer)
            VStack(alignment: .leading, spacing: 2) {
                Text(Str.t("home_live_asleep", name))
                    .font(.callout.weight(.medium))
                Text(SproutFormat.duration(millis: now - sleep.startTime).text)
                    .font(.footnote)
            }
            Spacer()
            Button(Str.t("sleep_woke_up"), action: onWake)
                .font(.footnote.weight(.medium))
        }
        .foregroundStyle(SproutColor.onPrimaryContainer)
        .padding(Spacing.snug)
        .background(SproutColor.primaryContainer, in: RoundedRectangle(cornerRadius: Radius.control))
    }
}

/// Before onboarding has run.
private struct NoBabyYet: View {
    var body: some View {
        VStack(spacing: Spacing.snug) {
            Text(Str.t("home_welcome_no_profile"))
                .font(.title2.weight(.semibold))
            Text(Str.t("home_setup_prompt"))
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(Spacing.section)
    }
}
