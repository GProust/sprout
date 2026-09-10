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
    /// The parent's own tracking, which decides whether the wellbeing tile and
    /// the You tab's two lower entries are there at all (BDR-0010).
    var tracksWellbeing = true
    var checkInPending = false
    private(set) var activeBabyId: Int64?

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
                    self?.tracksWellbeing = profile?.trackWellbeing ?? true
                    self?.activeBabyId = profile?.activeBabyId
                    self?.checkInPending = Self.isCheckInPending(profile)
                    self?.recompute()
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

    /// The baby the Baby tab is showing.
    ///
    /// Falls back to the first tracked baby: a selection can go stale when the
    /// baby it named is archived or deleted from another screen.
    var selectedBaby: BabySummary? {
        babies.first { $0.baby.id == activeBabyId } ?? babies.first
    }

    /// Whether today's check-in is still waiting.
    ///
    /// Through ``shouldOfferCheckIn`` rather than re-deciding it here: when it is
    /// offered is a product rule (BDR-0006) with a test on each platform, and a
    /// second copy of it on a screen is how the two quietly come apart.
    private static func isCheckInPending(_ profile: ParentProfile?) -> Bool {
        guard let profile else { return false }
        return shouldOfferCheckIn(
            trackWellbeing: profile.trackWellbeing,
            lastCheckIn: profile.lastCheckIn,
            now: Clock.millis
        )
    }

    /// "Not today": put the check-in away until tomorrow, saving nothing.
    func dismissCheckIn() {
        try? repository.updateParentLastCheckIn(Clock.millis)
    }

    /// Start a feed on the baby whose card was tapped.
    ///
    /// The selection is set first and the timer started after, because every
    /// write resolves the active baby as it inserts — a feed started from a card
    /// has to land on that card's baby, not on whoever was selected before.
    func startFeed(for baby: Baby, on side: BreastSide) {
        select(baby)
        NursingSessionStore.shared.startIfIdle(
            NursingSession(sessionStart: Clock.millis, currentSide: side, segmentStart: Clock.millis)
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

/// The household dashboard.
struct HomeScreen: View {
    let model: HomeViewModel
    let onOpen: (LogDestination) -> Void

    var body: some View {
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

                    // A baby asleep right now is the one thing on this screen
                    // that changes without anyone touching it.
                    if let sleeping = model.babies.first(where: { $0.ongoingSleep != nil }),
                       let sleep = sleeping.ongoingSleep {
                        LiveSleepRow(name: sleeping.baby.name, sleep: sleep, now: model.now) {
                            model.wakeUp(sleep)
                        }
                    }

                    if let single = model.babies.count == 1 ? model.babies.first : nil {
                        // One baby: nothing to disambiguate, so the dashboard
                        // *is* the baby view and nothing is a tap further away
                        // than it used to be (BDR-0009).
                        BabyPane(
                            summary: single,
                            tracksWellbeing: model.tracksWellbeing,
                            now: model.now,
                            onFeed: { model.startFeed(for: single.baby, on: $0); onOpen(.feeding) },
                            onOpen: onOpen,
                            onShareRecord: { onOpen(.report) },
                            header: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(single.baby.name)
                                        .font(.title2.weight(.bold))
                                        .foregroundStyle(SproutColor.onSurface)
                                    Text(SproutFormat.age(birthDate: single.baby.birthDate, now: model.now).text)
                                        .font(.callout)
                                        .foregroundStyle(SproutColor.onSurfaceVariant)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        )
                    } else {
                        // Two or more: a card each, and the full pane moves to
                        // the baby's own tab.
                        ForEach(model.babies) { summary in
                            BabyCardView(
                                summary: summary,
                                now: model.now,
                                onOpen: { model.select(summary.baby) },
                                onFeed: { model.startFeed(for: summary.baby, on: $0); onOpen(.feeding) }
                            )
                        }
                    }

                    if model.checkInPending {
                        CheckInCard(
                            onCheckIn: { onOpen(.checkIn) },
                            onDismiss: { model.dismissCheckIn() }
                        )
                    }
                }
            }
            .padding(Spacing.regular)
        }
        .navigationTitle(Str.t("app_name"))
        .navigationBarTitleDisplayMode(.inline)
        .sproutStyle()
        // A minute is as fine as "2 h ago" needs; anything shorter spends
        // battery redrawing the same words.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                model.tick()
            }
        }
    }
}

/// The daily check-in, waiting rather than interrupting (BDR-0006).
///
/// It sits on the dashboard instead of opening at launch, and "not today" puts
/// it away without saving anything — there is nothing to dismiss during a 3 a.m.
/// feed.
private struct CheckInCard: View {
    let onCheckIn: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            Text(Str.t("home_checkin_title"))
                .font(.subheadline.weight(.semibold))
            Text(Str.t("home_checkin_body"))
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            HStack {
                Button(Str.t("home_checkin_action"), action: onCheckIn)
                    .buttonStyle(.borderedProminent)
                Button(Str.t("home_checkin_dismiss"), action: onDismiss)
                    .buttonStyle(.bordered)
            }
            .padding(.top, Spacing.hairline)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
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
