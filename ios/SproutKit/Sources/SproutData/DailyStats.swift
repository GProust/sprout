import Foundation

/// Turning the logs into "what did a day actually look like", from
/// `ui/stats/DailyStats.kt`.
///
/// Everything here is a pure function over records the caller has already
/// filtered to one baby, so it is testable without a database, a clock or a
/// device — which matters, because CI is the only place this project builds
/// (ADR-0006).
///
/// Two rules decide which day an entry lands on, and they differ on purpose:
///
/// - **A feed or a change belongs to the day it started.** They are short; a
///   feed at 23:55 counting entirely towards that evening is what a parent would
///   say happened.
/// - **A sleep is split across the days it covers.** They are not short: a night
///   from 20:00 to 06:00 is ten hours, and putting all ten on the evening would
///   leave the following day looking sleepless. So each day gets the part of the
///   night that fell inside it, while the *count* of sleeps still goes to the day
///   the baby dropped off — "three naps" means three times settled, not three
///   fragments of clock.

/// One calendar day's totals, in local time.
///
/// Amounts are only ever the recorded ones: a bottle logged without its
/// millilitres, or a purée without its grams, adds to the *count* and leaves the
/// quantity alone. A day where nothing was weighed reads as 0 g because nothing
/// was weighed, not because the baby ate nothing — which is why the screen shows
/// counts and quantities side by side rather than deriving one from the other.
public struct DayStats: Equatable, Sendable {
    public var date: CalendarDay
    public var breastCount: Int = 0
    /// Time at the breast, summed over the day's sessions.
    public var breastMillis: Int64 = 0
    public var bottleCount: Int = 0
    public var bottleMl: Int = 0
    public var solidCount: Int = 0
    public var solidGrams: Int = 0
    /// How many times the baby went to sleep — counted on the day they dropped off.
    public var sleepCount: Int = 0
    /// How much of the day was spent asleep; a night is split across its two days.
    public var sleepMillis: Int64 = 0
    /// Changes with urine present. A change can be both wet and dirty.
    public var wetCount: Int = 0
    /// Changes with stool present.
    public var dirtyCount: Int = 0
    /// Changes that were both at once — kept apart so a stacked bar can add up to
    /// ``diaperCount`` instead of counting every mixed change twice.
    public var bothCount: Int = 0
    /// Changes logged, however they were made up.
    public var diaperCount: Int = 0

    public init(date: CalendarDay) { self.date = date }

    /// Changes with urine and no stool — the bottom segment of the stacked bar.
    public var wetOnlyCount: Int { wetCount - bothCount }

    /// Changes with stool and no urine — the top segment.
    public var dirtyOnlyCount: Int { dirtyCount - bothCount }

    /// Feeds of every kind — the "how many times did they eat today" number.
    public var feedCount: Int { breastCount + bottleCount + solidCount }
}

/// How long a breastfeed lasted, in milliseconds.
///
/// Three generations of the record have to answer this: sessions timed stretch
/// by stretch (the segments), the per-side totals that came before them, and the
/// plain start/end of a feed entered by hand. Anything that would come out
/// negative — a session still running, an end typed before its start — counts as
/// nothing rather than as a subtraction.
public func breastfeedMillis(_ entry: Feeding) -> Int64 {
    let segments = entry.nursingSegments
    if !segments.isEmpty {
        return segments.reduce(0) { $0 + max(0, $1.endTime - $1.startTime) }
    }
    if entry.leftDurationMs != nil || entry.rightDurationMs != nil {
        return max(0, entry.leftDurationMs ?? 0) + max(0, entry.rightDurationMs ?? 0)
    }
    if let end = entry.endTime {
        return max(0, end - entry.startTime)
    }
    return 0
}

/// The stretch of days a chart is showing: `from` through `to`, inclusive.
public struct StatsWindow: Equatable, Sendable {
    public var from: CalendarDay
    public var to: CalendarDay

    public init(from: CalendarDay, to: CalendarDay) {
        self.from = from
        self.to = to
    }
}

/// The window of `days` ending `offset` days before `today`.
///
/// An offset of 0 ends today, and the window starts `days - 1` days earlier — so
/// "7 days" is this week including today rather than eight columns. A larger
/// offset walks the whole window into the past, which is what dragging the
/// charts sideways does.
///
/// **It never reaches back past `birthDay`, and that is not cosmetic**: the days
/// in the window are what the averages divide by, so a twelve-day-old read over
/// thirty days would have every figure halved by eighteen days they did not live
/// — a newborn feeding ten times a day would be reported as feeding four. The
/// window shrinking is the honest answer, and the screen says which days it
/// actually covered.
///
/// A birth date in the future — typed ahead of a due date — still leaves today,
/// rather than a window that ends before it starts.
public func statsWindow(
    today: CalendarDay,
    days: Int,
    birthDay: CalendarDay?,
    offset: Int = 0
) -> StatsWindow {
    let bounded = min(max(offset, 0), maxStatsOffset(today: today, birthDay: birthDay))
    let end = today.adding(days: -bounded)

    var from = end.adding(days: -max(days - 1, 0))
    if let birthDay, birthDay > from { from = birthDay }
    // A birth date in the future leaves today rather than a window that ends
    // before it starts.
    if from > end { from = end }
    return StatsWindow(from: from, to: end)
}

/// How far back the window can be walked: to the day the baby was born, and no
/// further. Without a birth date — no baby yet — it stays put.
public func maxStatsOffset(today: CalendarDay, birthDay: CalendarDay?) -> Int {
    guard let birthDay, birthDay < today else { return 0 }
    return max(0, birthDay.days(until: today))
}

/// One ``DayStats`` per calendar day from `from` to `to` inclusive, oldest first.
///
/// Days with nothing logged are present and empty rather than missing: a gap in
/// a bar chart is information, and an average over "the days that had entries"
/// would quietly flatter a day the baby was in hospital and nobody was logging.
///
/// `now` closes a sleep that is still running, so an ongoing nap counts up to the
/// moment the screen is looking, and no further.
public func dailyStats(
    feedings: [Feeding],
    sleeps: [Sleep],
    diapers: [Diaper],
    from: CalendarDay,
    to: CalendarDay,
    now: Int64
) -> [DayStats] {
    guard from <= to else { return [] }

    var days: [CalendarDay] = []
    var cursor = from
    while cursor <= to {
        days.append(cursor)
        cursor = cursor.adding(days: 1)
    }

    var building: [CalendarDay: DayStats] = [:]
    for day in days { building[day] = DayStats(date: day) }

    for entry in feedings {
        let day = CalendarDay(millis: entry.startTime)
        guard building[day] != nil else { continue }
        switch entry.type {
        case .BREAST:
            building[day]!.breastCount += 1
            building[day]!.breastMillis += breastfeedMillis(entry)
        case .BOTTLE:
            building[day]!.bottleCount += 1
            building[day]!.bottleMl += entry.amountMl ?? 0
        case .SOLID:
            building[day]!.solidCount += 1
            building[day]!.solidGrams += entry.amountGrams ?? 0
        }
    }

    for entry in sleeps {
        let startedOn = CalendarDay(millis: entry.startTime)
        if building[startedOn] != nil { building[startedOn]!.sleepCount += 1 }
        // An unfinished sleep runs up to `now`; one whose end predates its start
        // — a typo, or a clock that moved — contributes no time at all.
        let end = max(entry.endTime ?? now, entry.startTime)
        for (day, millis) in splitByDay(start: entry.startTime, end: end) {
            if building[day] != nil { building[day]!.sleepMillis += millis }
        }
    }

    for entry in diapers {
        let day = CalendarDay(millis: entry.time)
        guard building[day] != nil else { continue }
        building[day]!.diaperCount += 1
        if entry.wet { building[day]!.wetCount += 1 }
        if entry.dirty { building[day]!.dirtyCount += 1 }
        if entry.wet && entry.dirty { building[day]!.bothCount += 1 }
    }

    return days.map { building[$0]! }
}

// MARK: - What a single day was made of, for the detail behind a tapped bar

/// The feeds that started on `day`, oldest first.
public func feedsOn(_ feedings: [Feeding], day: CalendarDay) -> [Feeding] {
    feedings
        .filter { CalendarDay(millis: $0.startTime) == day }
        .sorted { $0.startTime < $1.startTime }
}

/// The changes logged on `day`, oldest first.
public func diapersOn(_ diapers: [Diaper], day: CalendarDay) -> [Diaper] {
    diapers
        .filter { CalendarDay(millis: $0.time) == day }
        .sorted { $0.time < $1.time }
}

/// The sleeps that *touch* `day` — a night started the evening before belongs in
/// the day it woke into, which is the whole point of splitting it.
public func sleepsOn(_ sleeps: [Sleep], day: CalendarDay, now: Int64) -> [Sleep] {
    sleeps
        .filter { sleepMillisOn($0, day: day, now: now) > 0 }
        .sorted { $0.startTime < $1.startTime }
}

/// How much of `sleep` fell inside `day`; 0 if none of it did.
public func sleepMillisOn(_ sleep: Sleep, day: CalendarDay, now: Int64) -> Int64 {
    let end = max(sleep.endTime ?? now, sleep.startTime)
    return splitByDay(start: sleep.startTime, end: end)[day] ?? 0
}

// MARK: - Averages

/// Per-day averages over the window.
///
/// **Today is left out**: it is still being lived, and a morning's worth of feeds
/// averaged in with whole days would drag every figure down and make a normal
/// week look like a declining one. When today is all there is — a fresh install,
/// or a one-day window — it is used rather than showing nothing.
public struct StatsAverages: Equatable, Sendable {
    /// How many days the averages are actually over.
    public var dayCount: Int = 0
    public var feedsPerDay: Double = 0
    public var breastfeedsPerDay: Double = 0
    public var breastMillisPerDay: Int64 = 0
    public var bottlesPerDay: Double = 0
    public var bottleMlPerDay: Int = 0
    public var solidsPerDay: Double = 0
    public var solidGramsPerDay: Int = 0
    public var sleepsPerDay: Double = 0
    public var sleepMillisPerDay: Int64 = 0
    public var diapersPerDay: Double = 0
    public var wetPerDay: Double = 0
    public var dirtyPerDay: Double = 0

    public init() {}
}

/// The averages of `days`, counting only the days before `today`.
public func averagesOf(_ days: [DayStats], today: CalendarDay) -> StatsAverages {
    let complete = days.filter { $0.date < today }
    let counted = complete.isEmpty ? days : complete
    guard !counted.isEmpty else { return StatsAverages() }

    let n = Double(counted.count)
    func mean(_ of: (DayStats) -> Int64) -> Double {
        Double(counted.reduce(Int64(0)) { $0 + of($1) }) / n
    }

    var averages = StatsAverages()
    averages.dayCount = counted.count
    averages.feedsPerDay = mean { Int64($0.feedCount) }
    averages.breastfeedsPerDay = mean { Int64($0.breastCount) }
    averages.breastMillisPerDay = Int64(mean { $0.breastMillis })
    averages.bottlesPerDay = mean { Int64($0.bottleCount) }
    averages.bottleMlPerDay = Int(mean { Int64($0.bottleMl) })
    averages.solidsPerDay = mean { Int64($0.solidCount) }
    averages.solidGramsPerDay = Int(mean { Int64($0.solidGrams) })
    averages.sleepsPerDay = mean { Int64($0.sleepCount) }
    averages.sleepMillisPerDay = Int64(mean { $0.sleepMillis })
    averages.diapersPerDay = mean { Int64($0.diaperCount) }
    averages.wetPerDay = mean { Int64($0.wetCount) }
    averages.dirtyPerDay = mean { Int64($0.dirtyCount) }
    return averages
}

/// How a stretch from `start` to `end` divides between the calendar days it
/// touches, in milliseconds. A stretch inside one day yields that one day.
///
/// Day boundaries are asked of the calendar rather than counted in 24-hour steps,
/// so the short and long days either side of a daylight-saving change come out at
/// 23 and 25 hours, as they were actually lived.
func splitByDay(start: Int64, end: Int64) -> [CalendarDay: Int64] {
    guard end > start else { return [:] }
    var result: [CalendarDay: Int64] = [:]
    var day = CalendarDay(millis: start)
    let lastDay = CalendarDay(millis: end)
    while day <= lastDay {
        let dayStart = day.startMillis()
        let dayEnd = day.adding(days: 1).startMillis()
        let overlap = min(end, dayEnd) - max(start, dayStart)
        if overlap > 0 { result[day] = overlap }
        day = day.adding(days: 1)
    }
    return result
}

// MARK: - How the sleeps happened: where, and how they were lying (BDR-0014)

/// Where a sleep happened, as the breakdown groups it.
///
/// The six offered places group themselves. A place the parent named — `.OTHER`
/// with a note — groups by that name, case and surrounding spaces ignored:
/// someone who logs "pram" a dozen times wants to see "pram", not a dozen sleeps
/// filed under "somewhere else". `.OTHER` with nothing typed stays ``offered``,
/// which is exactly what it says.
public enum SleepWhere: Hashable, Sendable {
    /// One of the places Sprout offers.
    case offered(SleepPlace)
    /// A place the parent named themselves, spelled as they first typed it.
    case named(String)
}

/// How a sleep says where it happened, or `nil` when it doesn't say.
public func sleepWhere(_ entry: Sleep) -> SleepWhere? {
    guard let place = entry.place else { return nil }
    guard place == .OTHER else { return .offered(place) }
    let named = entry.placeNote?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return named.isEmpty ? .offered(.OTHER) : .named(named)
}

/// One line of a breakdown: how many sleeps `value` accounts for, and how much
/// sleep time. A `nil` `value` is the sleeps that didn't say.
///
/// The two numbers answer different questions and are counted the way the rest
/// of the statistics count them: a sleep is *counted* on the day it started, so a
/// night begun before the window adds its hours here without adding to the tally
/// of times settled.
public struct SleepSlice<Value: Hashable & Sendable>: Equatable, Sendable {
    public var value: Value?
    public var count: Int
    public var millis: Int64

    public init(value: Value?, count: Int, millis: Int64) {
        self.value = value
        self.count = count
        self.millis = millis
    }
}

/// How the window's sleep divides by place and by position.
///
/// Both lists cover **every** sleep the window saw, the ones with nothing
/// recorded included as a `nil` slice at the end — so the shares add up to the
/// sleep time the card is already showing rather than to a subset of it. A screen
/// that quietly dropped the unrecorded ones would report "80% in their own bed"
/// off two logged naps out of ten.
public struct SleepBreakdown: Equatable, Sendable {
    public var byPlace: [SleepSlice<SleepWhere>] = []
    public var byPosition: [SleepSlice<SleepPosition>] = []
    /// Sleeps that started inside the window.
    public var totalCount: Int = 0
    /// Sleep time inside the window — the same total the daily figures add to.
    public var totalMillis: Int64 = 0

    public init() {}

    /// True when at least one sleep in the window says where it happened.
    public var hasPlaces: Bool { byPlace.contains { $0.value != nil } }

    /// True when at least one sleep in the window says how the baby was lying.
    public var hasPositions: Bool { byPosition.contains { $0.value != nil } }
}

/// The breakdown of every sleep touching `from`…`to`, inclusive.
///
/// `now` closes a sleep that is still running, as it does everywhere else, so an
/// ongoing nap counts up to the moment the screen is looking and no further.
public func sleepBreakdown(
    _ sleeps: [Sleep],
    from: CalendarDay,
    to: CalendarDay,
    now: Int64
) -> SleepBreakdown {
    guard from <= to else { return SleepBreakdown() }

    let windowStart = from.startMillis()
    let windowEnd = to.adding(days: 1).startMillis()

    var places = Tallies<SleepWhere>()
    var positions = Tallies<SleepPosition>()
    var totalCount = 0
    var totalMillis: Int64 = 0

    for entry in sleeps {
        let end = max(entry.endTime ?? now, entry.startTime)
        let millis = max(0, min(end, windowEnd) - max(entry.startTime, windowStart))
        let startedHere = entry.startTime >= windowStart && entry.startTime < windowEnd
        if !startedHere && millis == 0 { continue }

        if startedHere { totalCount += 1 }
        totalMillis += millis

        let where_ = sleepWhere(entry)
        places.add(key: whereKey(where_), value: where_, counted: startedHere, millis: millis)
        positions.add(
            key: entry.position?.rawValue ?? unrecordedKey,
            value: entry.position,
            counted: startedHere,
            millis: millis
        )
    }

    var breakdown = SleepBreakdown()
    breakdown.byPlace = places.ordered()
    breakdown.byPosition = positions.ordered()
    breakdown.totalCount = totalCount
    breakdown.totalMillis = totalMillis
    return breakdown
}

/// The grouping key of a place. The prefixes keep a *named* "own bed" out of the
/// offered one — two different answers that happen to share a word.
private func whereKey(_ where_: SleepWhere?) -> String {
    switch where_ {
    case nil: return unrecordedKey
    case .offered(let place): return "offered:\(place.rawValue)"
    case .named(let name): return "named:\(name.lowercased())"
    }
}

/// The key of the slice holding everything that didn't say; no enum name is empty.
private let unrecordedKey = ""

/// Insertion-ordered tallies, so the first spelling of a named place is the one
/// shown — someone who typed "Pram" then "pram" sees their own first word.
private struct Tallies<Value: Hashable & Sendable> {
    private var order: [String] = []
    private var byKey: [String: SleepSlice<Value>] = [:]

    mutating func add(key: String, value: Value?, counted: Bool, millis: Int64) {
        if byKey[key] == nil {
            order.append(key)
            byKey[key] = SleepSlice(value: value, count: 0, millis: 0)
        }
        if counted { byKey[key]!.count += 1 }
        byKey[key]!.millis += millis
    }

    /// Longest first, with the sleeps that said nothing last whatever their size.
    ///
    /// The insertion index is the last tiebreak because `sorted(by:)` is not
    /// stable, unlike Kotlin's `sortedWith` — without it two places with equal
    /// time could swap between renders of the same data.
    func ordered() -> [SleepSlice<Value>] {
        order.enumerated()
            .compactMap { index, key in byKey[key].map { (index, $0) } }
            .sorted { a, b in
                if (a.1.value == nil) != (b.1.value == nil) { return b.1.value == nil }
                if a.1.millis != b.1.millis { return a.1.millis > b.1.millis }
                if a.1.count != b.1.count { return a.1.count > b.1.count }
                return a.0 < b.0
            }
            .map(\.1)
    }
}
