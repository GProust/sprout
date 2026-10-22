import Foundation

/// The guided tour's steps, in the order they are shown (BDR-27): each a short
/// card pointing at one area of the dashboard.
///
/// The same steps, in the same order and with the same words, as `TourStep` in
/// `data/Tour.kt`.
public enum TourStep: CaseIterable, Sendable {
    /// The log tiles: feed, sleep, nappy and the rest.
    case logTiles
    /// Starting a breastfeed, and where the running timer then shows.
    case breastfeed
    /// The tab bar: Trends, You, and a baby's own tab once there are two.
    case bottomBar
    /// Settings, and sharing with the other parent's phone.
    case settings
}

/// What this phone has stored about the tour, in the ordinary settings. From
/// `TourMark` in `data/Tour.kt`, spelled the same way.
///
/// Nothing stored is the commonest answer, and it means *never*: a phone set up
/// before the tour existed has a profile and no mark, and so does one restored
/// from its backup. Only finishing onboarding writes ``pending``, which is how
/// "only a phone that has just been set up" falls out without asking anything
/// else.
public enum TourMark: String, Sendable {
    /// Onboarding has just finished, and the dashboard owes the tour.
    case pending
    /// The tour has been offered: taken, skipped, or given way to a file.
    case done
}

/// What is asking for the tour.
public enum TourTrigger: Sendable {
    /// The dashboard coming up for the first time after a launch.
    case arrival
    /// *Replay the tour*, in Settings.
    case replay
}

/// The steps a dashboard listing `babyCount` babies can point at.
///
/// A step that would point at nothing is left out rather than shown over an
/// empty space. With no baby yet there are no tiles and no feed buttons, and the
/// dashboard's own card already asks for a baby. With two or more, the tiles
/// move to each baby's own tab (BDR-9), which the tab bar's step names.
public func tourSteps(babyCount: Int) -> [TourStep] {
    if babyCount <= 0 { return [.bottomBar, .settings] }
    if babyCount == 1 { return TourStep.allCases }
    return [.breastfeed, .bottomBar, .settings]
}

/// The step after `step`, or nil when `step` was the last and the tour is over.
public func tourStepAfter(_ step: TourStep, in steps: [TourStep]) -> TourStep? {
    guard let index = steps.firstIndex(of: step), index + 1 < steps.count else { return nil }
    return steps[index + 1]
}

/// Whether the tour starts.
///
/// On ``TourTrigger/arrival`` it starts only for a phone that owes it — one that
/// has just finished onboarding — and only when the launch was not for
/// something else. A file handed to Sprout goes where it said, and that wins
/// (ADR-0008 for a second phone joining a household): the parent did not open
/// the app to be shown round it. A replay is the parent asking, so it starts
/// whatever is stored, on a phone that never had the tour as much as on one
/// that did.
public func tourStarts(_ trigger: TourTrigger, mark: TourMark?, openedForSomethingElse: Bool) -> Bool {
    switch trigger {
    case .replay: return true
    case .arrival: return mark == .pending && !openedForSomethingElse
    }
}

/// What is stored once the dashboard has come up: an owed tour is spent
/// whether or not it started.
///
/// It is offered on the launch onboarding finished on and on no other, because
/// any later launch could be the one in the middle of a feed (BDR-6). A tour
/// that gave way to a file does not come back on the next launch either;
/// Settings has it.
public func tourMarkAfterArrival(_ mark: TourMark?) -> TourMark? {
    if mark == .pending { return .done }
    return mark
}

/// Whether a tour that is up gets out of the way.
///
/// It does the moment the parent goes anywhere — a tile, a tab, Settings, or a
/// file arriving underneath it — because the tour is about the dashboard and
/// there is nothing left to point at. And it does the moment a feed starts:
/// someone feeding a baby is not reading cards. Only a feed that *starts*
/// counts — a baby feeding now who was not a moment ago, which is also a twin's
/// feed starting beside the other's (BDR-23) — so a replay asked for with a
/// feed already running is not cut short by it.
///
/// `feedingBefore` and `feedingNow` are the babies with a feed running, by uid.
public func tourStepsAside(onDashboard: Bool, feedingBefore: Set<String?>, feedingNow: Set<String?>) -> Bool {
    !onDashboard || !feedingNow.isSubset(of: feedingBefore)
}

/// Whether this phone owes the guided tour, or has had it, from
/// `TourSettings.kt`.
///
/// In the ordinary settings (``UserDefaultsStore``), not the Keychain: having
/// been shown round the app is about a person, not about this handset, so a
/// restored phone carries it like any other setting (ADR-0011, ADR-0018). A
/// restored phone also skips onboarding — its profile came with it — so it
/// never owes the tour either way. Not synced: the other parent has been shown
/// nothing on this phone.
public struct TourSettings {
    private static let key = "tour"

    private let store: any DeviceLocalStore

    public init(store: any DeviceLocalStore) {
        self.store = store
    }

    /// What is stored, or nil when nothing is: a phone that never owed the tour.
    /// Anything unrecognised reads as nothing.
    public var mark: TourMark? {
        store.string(forKey: Self.key).flatMap(TourMark.init(rawValue:))
    }

    /// Onboarding has just finished, so the dashboard owes the tour. Written
    /// before the profile is saved, because saving it is what opens the
    /// dashboard.
    public func markPending() {
        set(.pending)
    }

    /// Writes `mark`, or clears the key when it is nil.
    public func set(_ mark: TourMark?) {
        store.setString(mark?.rawValue, forKey: Self.key)
    }
}
