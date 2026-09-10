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
    @Environment(\.sprout) private var sprout
    @State private var model: HomeViewModel?
    @State private var selection: Tab = .home
    @State private var homePath: [LogDestination] = []
    @State private var babyPath: [LogDestination] = []
    @State private var youPath: [LogDestination] = []

    var body: some View {
        Group {
            if let model { tabs(model) } else { Color.clear }
        }
        .task {
            let model = model ?? HomeViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func tabs(_ model: HomeViewModel) -> some View {
        TabView(selection: $selection) {
            NavigationStack(path: $homePath) {
                HomeScreen(model: model, onOpen: open(_:))
                    .navigationDestination(for: LogDestination.self, destination: screen(for:))
            }
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
        case .wellbeing: HealthScreen()
        case .checkIn: DailyCheckInScreen()
        case .report(let babyId): ReportScreen(babyId: babyId)
        case .stats: StatsScreen()
        case .profile: ProfileScreen()
        case .settings: SettingsScreen()
        case .sync: SyncScreen()
        }
    }
}
