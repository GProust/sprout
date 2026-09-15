import Foundation

/// One baby's line on the dashboard.
///
/// The dashboard shows every tracked baby at once (BDR-0009), not just the
/// selected one, so this is computed per baby from a single household-wide read.
public struct BabySummary: Equatable, Identifiable, Sendable {
    public let baby: Baby
    public let lastFeed: Int64?
    /// The last sleep that *finished*. One still running is ``ongoingSleep``.
    public let lastSleep: Int64?
    public let lastDiaper: Int64?
    public let feedsToday: Int
    public let sleepTodayMs: Int64
    public let diapersToday: Int
    /// Which breast to offer next, when that can be answered.
    public let nextSide: BreastSide?
    public let ongoingSleep: Sleep?

    public var id: Int64 { baby.id ?? 0 }
}

/// How far back the app still cares which breast the last breastfeed began on.
public let lastBreastWindowMs: Int64 = 24 * 60 * 60 * 1000

/// Summarises every tracked baby, from `ui/home/HouseholdSummary.kt`.
public func summariseHousehold(
    babies: [Baby],
    feedings: [Feeding],
    sleeps: [Sleep],
    diapers: [Diaper],
    ongoingSleeps: [Sleep],
    dayStart: Int64,
    now: Int64
) -> [BabySummary] {
    let feedsBy = Dictionary(grouping: feedings, by: \.babyId)
    let sleepsBy = Dictionary(grouping: sleeps, by: \.babyId)
    let diapersBy = Dictionary(grouping: diapers, by: \.babyId)
    let ongoingBy = Dictionary(grouping: ongoingSleeps, by: \.babyId)

    return babies.map { baby in
        let id = baby.id ?? 0
        let babyFeeds = feedsBy[id] ?? []
        let babySleeps = sleepsBy[id] ?? []
        let babyDiapers = diapersBy[id] ?? []

        return BabySummary(
            baby: baby,
            lastFeed: babyFeeds.map(\.startTime).max(),
            // A sleep still running is reported separately; "last slept" is
            // about the last one that finished.
            lastSleep: babySleeps.compactMap(\.endTime).max(),
            lastDiaper: babyDiapers.map(\.time).max(),
            feedsToday: babyFeeds.filter { $0.startTime >= dayStart }.count,
            sleepTodayMs: sleepMillis(in: babySleeps, since: dayStart, now: now),
            diapersToday: babyDiapers.filter { $0.time >= dayStart }.count,
            nextSide: nextBreast(feeds: babyFeeds, now: now),
            ongoingSleep: (ongoingBy[id] ?? []).max { $0.startTime < $1.startTime }
        )
    }
}

/// How much of `sleeps` falls on or after `dayStart`, counting one still running
/// as running up to `now`.
///
/// Matches what the daily statistics do, so the dashboard and the Trends screen
/// can never disagree about the same day.
public func sleepMillis(in sleeps: [Sleep], since dayStart: Int64, now: Int64) -> Int64 {
    sleeps
        .filter { $0.startTime >= dayStart }
        .reduce(0) { total, sleep in total + max(0, (sleep.endTime ?? now) - sleep.startTime) }
}

/// Which breast to offer next: the other one from where the last breastfeed
/// *began*.
///
/// A session that went left then right still counts as left, and a bottle given
/// in between does not take the side with it. `nil` once nothing has been nursed
/// within a day, since by then there is no alternation left to continue.
public func nextBreast(feeds: [Feeding], now: Int64) -> BreastSide? {
    let lastBreast = feeds
        .filter { $0.type == .BREAST && now - $0.startTime <= lastBreastWindowMs }
        .max { $0.startTime < $1.startTime }

    guard let lastBreast else { return nil }

    switch firstNursedSide(of: lastBreast) {
    case .LEFT: return .RIGHT
    case .RIGHT: return .LEFT
    // BOTH on an older entry says which breasts were used but not which came
    // first, so it settles nothing; so does a session with no side at all.
    default: return nil
    }
}

/// Which breast a session started on — the first segment's side if the timer
/// recorded any, otherwise whatever the entry itself says.
public func firstNursedSide(of feed: Feeding) -> BreastSide? {
    feed.nursingSegments.first?.side ?? feed.side
}
