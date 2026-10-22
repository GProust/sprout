import SproutData
import SwiftUI

/// The four places the app is organised into (BDR-0010).
///
/// **Kinds of place, not kinds of log.** The bar used to hold the five
/// baby-centred log screens, which could not quite be implemented: five entities
/// carry a `babyId`, Home takes a seat, and a navigation bar holds five — so
/// treatments fell out of the bar into a list next to pumping and wellbeing,
/// which were there for a completely different reason, with nothing on screen to
/// tell the two cases apart. The logs now live in the dashboard's grid as
/// equals, where there is no seat to lose.
enum Tab: Hashable, CaseIterable {
    case home
    /// The picked child. Present only with two or more babies — with one, Home
    /// already *is* that view (BDR-0009).
    case baby
    /// The statistics screen, promoted out of the button it was hiding behind.
    case trends
    /// Pumping, the wellbeing history and the daily check-in, together — saying
    /// out loud what the data model already decided (BDR-0001, BDR-0007).
    case you

    var label: String {
        switch self {
        case .home: return Str.t("nav_home")
        case .baby: return Str.t("nav_baby")
        case .trends: return Str.t("nav_trends")
        case .you: return Str.t("nav_you")
        }
    }

    /// The Baby tab wears the selected child's name once there is one, which is
    /// the whole reason that tab exists: "Baby" beside a bar that already says
    /// Home and You names nothing, and with two children the answer to "whose
    /// screen is this" has to be on the tab itself.
    func label(activeBabyName: String?) -> String {
        guard self == .baby, let name = activeBabyName, !name.isEmpty else { return label }
        return name
    }

    var systemImage: String {
        switch self {
        case .home: return "house.fill"
        case .baby: return "person.fill"
        case .trends: return "chart.bar.fill"
        case .you: return "heart.fill"
        }
    }
}

/// The shell.
///
/// Each tab keeps its own `NavigationStack`, which is what SwiftUI gives for
/// free where Compose needed the multiple-back-stack pattern spelled out: a log
/// screen opened from Home stays on Home's stack, and switching to Trends and
/// back finds it where it was left.
struct RootView: View {
    /// What to show right after launch (`ui/startup/StartupViewModel.kt`).
    ///
    /// Only the first run stops on anything. The daily check-in waits on the
    /// dashboard rather than gating the app, so opening Sprout mid-feed always
    /// lands straight on the main screen (BDR-0006).
    private enum Startup: Equatable {
        case loading
        case onboarding
        case main
    }

    @Environment(\.sprout) private var sprout
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model: HomeViewModel?
    @State private var startup: Startup = .loading
    @State private var selection: Tab = .home
    @State private var homePath: [LogDestination] = []
    @State private var babyPath: [LogDestination] = []
    @State private var youPath: [LogDestination] = []

    /// The guided tour (BDR-27): the step on screen, if one is, and the steps
    /// it was started with — which depend on how many babies the dashboard
    /// lists.
    @State private var tourStep: TourStep?
    @State private var tourPlan: [TourStep] = []
    @State private var tourTargets = TourTargets()
    /// Whether the dashboard has come up on this launch yet. The tour is
    /// decided once, the first time it does.
    @State private var arrived = false

    var body: some View {
        Group {
            switch startup {
            case .onboarding:
                OnboardingScreen { answers in
                    // The observation below carries the app forward; there is no
                    // navigation to perform, because the profile appearing *is*
                    // the app no longer being on its first run.
                    //
                    // A throw here leaves the parent on the last step with the
                    // button still live, which is the right affordance: the only
                    // way these two writes fail is a database that is already
                    // broken, and `SproutApp` says so at launch when it is.
                    // Tapping again is a retry.
                    //
                    // Everything is new to someone who has just arrived, and
                    // nothing has changed: a fresh install starts with no
                    // "what's new" to catch up on (BDR-22). Before the profile
                    // is written, because writing it is what opens the dashboard.
                    WhatsNewSettings(store: sprout.settingsStore).markAllSeen()
                    // This is also the one launch that cannot be the middle of
                    // a feed, so the dashboard about to open owes a short tour
                    // of itself (BDR-27). Nothing else ever writes it: a phone
                    // that already had a profile is never shown round.
                    TourSettings(store: sprout.settingsStore).markPending()
                    try? completeOnboarding(answers, into: sprout.repository)
                }
            case .main:
                if let model { tabs(model) } else { Color.clear }
            case .loading:
                // Not a spinner: the profile read takes a frame or two, and a
                // spinner that flashes is worse than a beat of the background.
                Color.clear.sproutStyle()
            }
        }
        // The gate. Android asks the same question of the same flow, and the
        // answer is the same: a phone with no parent profile has never been set
        // up, whatever else is in the database.
        .task {
            await observe(sprout.repository.parentProfile) { profile in
                startup = profile == nil ? .onboarding : .main
            }
        }
        .task {
            let model = model ?? HomeViewModel(repository: sprout.repository, settings: sprout.settingsStore)
            self.model = model
            model.adoptUnnamedFeed()
            await model.observeEverything()
        }
        // A file handed to Sprout by another app opens the sharing screen,
        // wherever in the app the parents happen to be. The screen itself reads
        // the file; this only gets them there.
        .onChange(of: sprout.pendingSyncFile) { _, url in
            guard url != nil else { return }
            selection = .home
            // Settings underneath, so the back arrow lands where the screen
            // would have been reached from rather than on the dashboard.
            homePath = [.settings, .sync]
        }
        // The dashboard has come up. Whether the tour starts is decided here,
        // once a launch, and an owed tour is spent either way.
        .onChange(of: startup) { _, stage in
            guard stage == .main, !arrived else { return }
            arrived = true
            startTour(.arrival)
        }
        // The tour gets out of the way the moment the parent goes anywhere — a
        // tile, a tab, Settings, a file arriving — or a feed starts.
        .onChange(of: TourSurroundings(
            onDashboard: onDashboard,
            feeding: Set(NursingSessionStore.shared.sessions.map(\.babyUid))
        )) { before, now in
            guard tourStep != nil,
                  tourStepsAside(
                      onDashboard: now.onDashboard,
                      feedingBefore: before.feeding,
                      feedingNow: now.feeding
                  )
            else { return }
            tourStep = nil
        }
    }

    /// The dashboard itself, with nothing pushed on top of it.
    private var onDashboard: Bool {
        selection == .home && homePath.isEmpty
    }

    /// Starts the tour if `trigger` should, on the steps the dashboard can show.
    private func startTour(_ trigger: TourTrigger) {
        let settings = TourSettings(store: sprout.settingsStore)
        let mark = settings.mark
        if trigger == .arrival {
            let spent = tourMarkAfterArrival(mark)
            if spent != mark { settings.set(spent) }
        }
        // A file handed to Sprout has already put the sharing screen on the
        // dashboard's stack, and what the parent opened the app for wins.
        let openedForSomethingElse = sprout.pendingSyncFile != nil || !homePath.isEmpty
        guard tourStarts(trigger, mark: mark, openedForSomethingElse: openedForSomethingElse) else { return }
        let steps = tourSteps(babyCount: (try? sprout.repository.activeBabies().count) ?? 0)
        tourPlan = steps
        tourStep = steps.first
    }

    /// Settings → *Replay the tour*: back to the dashboard it is about, then
    /// from the top.
    private func replayTour() {
        selection = .home
        homePath = []
        startTour(.replay)
    }

    /// Over the whole shell, tab bar included, and never inside the dashboard
    /// itself, so the dashboard's own captures never meet it.
    private var tourOverlay: some View {
        ZStack {
            if let step = tourStep, onDashboard {
                TourOverlay(
                    step: step,
                    position: (tourPlan.firstIndex(of: step) ?? 0) + 1,
                    count: tourPlan.count,
                    isLast: tourStepAfter(step, in: tourPlan) == nil,
                    target: tourTargets.frames[step],
                    onNext: { tourStep = tourStepAfter(step, in: tourPlan) },
                    onSkip: { tourStep = nil }
                )
                .transition(.opacity)
            }
        }
        // A short fade in and out, and none at all with Reduce Motion on.
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: tourStep)
    }

    @ViewBuilder
    private func tabs(_ model: HomeViewModel) -> some View {
        TabView(selection: $selection) {
            NavigationStack(path: $homePath) {
                HomeScreen(model: model, onOpen: open(_:))
                    .navigationDestination(for: LogDestination.self, destination: screen(for:))
                    // Where the dashboard's safe area ends at each edge, which
                    // is where the navigation bar and the tab bar begin. Both
                    // are UIKit's, with no view of their own to measure, so the
                    // tour's steps for them start here (`TourOverlay`). No
                    // height, so the dashboard is laid out exactly as before.
                    .safeAreaInset(edge: .top, spacing: 0) {
                        Color.clear.frame(height: 0).tourTarget(.settings)
                    }
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        Color.clear.frame(height: 0).tourTarget(.bottomBar)
                    }
            }
            // The dashboard is the only screen whose areas the tour points at.
            .environment(\.tourTargets, tourTargets)
            .tabItem { Label(Tab.home.label, systemImage: Tab.home.systemImage) }
            .tag(Tab.home)

            // Only once there is a choice to make. With one baby the dashboard
            // shows the same pane in place, so a second tab would be the same
            // screen twice.
            if model.babies.count > 1 {
                NavigationStack(path: $babyPath) {
                    BabyScreen(model: model, onOpen: open(_:))
                        .navigationDestination(for: LogDestination.self, destination: screen(for:))
                }
                .tabItem {
                    Label(
                        Tab.baby.label(activeBabyName: model.selectedBaby?.baby.name),
                        systemImage: Tab.baby.systemImage
                    )
                }
                .tag(Tab.baby)
            }

            NavigationStack {
                StatsScreen()
            }
            .tabItem { Label(Tab.trends.label, systemImage: Tab.trends.systemImage) }
            .tag(Tab.trends)

            NavigationStack(path: $youPath) {
                YouScreen(model: model, onOpen: { youPath.append($0) })
                    .navigationDestination(for: LogDestination.self, destination: screen(for:))
            }
            .tabItem { Label(Tab.you.label, systemImage: Tab.you.systemImage) }
            .tag(Tab.you)
        }
        .tint(SproutColor.primary)
        // While a step is up, what the scrim dims is hidden from VoiceOver as
        // well as from sight: the card is the only thing on the screen.
        .accessibilityHidden(tourStep != nil && onDashboard)
        .overlay { tourOverlay }
    }

    /// Opens a log on whichever tab asked for it, so a screen reached from the
    /// baby's tab comes back to the baby's tab.
    private func open(_ destination: LogDestination) {
        switch destination {
        case .stats:
            // Trends is a tab, not a push: sending it to a stack would put a
            // second copy of the statistics on top of Home.
            selection = .trends
        default:
            if selection == .baby { babyPath.append(destination) } else { homePath.append(destination) }
        }
    }

    /// Log screens are **pushed, not tabbed** (BDR-0010), and each gains a back
    /// arrow — so every one of them behaves the same way, which is the
    /// consistency the old bar was missing.
    @ViewBuilder
    private func screen(for destination: LogDestination) -> some View {
        switch destination {
        case .feeding: FeedingScreen()
        case .sleep: SleepScreen()
        case .diaper: DiaperScreen()
        case .growth: GrowthScreen()
        case .pumping: PumpingScreen()
        case .treatments: TreatmentsScreen()
        case .medicines: MedicinesScreen()
        case .foods: FoodsScreen()
        case .wellbeing: HealthScreen()
        case .checkIn: DailyCheckInScreen()
        case .report(let babyId): ReportScreen(babyId: babyId)
        case .stats: StatsScreen()
        case .profile: ProfileScreen()
        case .settings: SettingsScreen(onReplayTour: replayTour)
        case .sync: SyncScreen()
        case .customiseHome:
            // The dashboard's own model, because what this screen changes is
            // what the grid draws: one arrangement, held in one place, read by
            // Home and by each baby's tab alike (BDR-25).
            if let model { CustomiseHomeScreen(model: model) }
        }
    }
}

/// What the tour watches to know when to get out of the way, as one value so a
/// feed started from the dashboard — which also leaves it — is one change.
private struct TourSurroundings: Equatable {
    var onDashboard: Bool
    /// The babies with a feed running, by uid: a twin's feed starting beside
    /// the other's is a feed starting too (BDR-23).
    var feeding: Set<String?>
}
