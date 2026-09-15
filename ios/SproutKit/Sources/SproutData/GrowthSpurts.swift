import Foundation

/// The ages at which babies commonly go through a growth spurt, from
/// `ui/common/GrowthSpurts.kt`.
///
/// **These are tendencies, not a schedule.** Every baby is different, which is
/// why the app words them as "around this age" and never as something a baby is
/// late for. A parent whose three-week-old is feeding constantly is looking for
/// reassurance that this is normal, not a milestone chart to fall behind.
///
/// `startDay` and `endDay` are the baby's age in days, both inclusive. The label
/// is carried as a *number* rather than a phrase so each language can decline it
/// — "around 3 weeks" is not the same shape of sentence everywhere.
public struct GrowthSpurtWindow: Equatable, Sendable {
    public let startDay: Int
    public let endDay: Int
    public let approxWeeks: Int?
    public let approxMonths: Int?

    public init(startDay: Int, endDay: Int, approxWeeks: Int? = nil, approxMonths: Int? = nil) {
        self.startDay = startDay
        self.endDay = endDay
        self.approxWeeks = approxWeeks
        self.approxMonths = approxMonths
    }
}

/// Commonly cited growth-spurt periods over the first year, in age order.
public let growthSpurtWindows: [GrowthSpurtWindow] = [
    GrowthSpurtWindow(startDay: 7, endDay: 10, approxWeeks: 1),
    GrowthSpurtWindow(startDay: 14, endDay: 21, approxWeeks: 3),
    GrowthSpurtWindow(startDay: 40, endDay: 48, approxWeeks: 6),
    GrowthSpurtWindow(startDay: 60, endDay: 68, approxWeeks: 9),
    GrowthSpurtWindow(startDay: 85, endDay: 95, approxMonths: 3),
    GrowthSpurtWindow(startDay: 175, endDay: 186, approxMonths: 6),
    GrowthSpurtWindow(startDay: 265, endDay: 277, approxMonths: 9),
]

/// How many days ahead of a window the app starts saying "one may be coming".
public let growthSpurtHeadsUpDays = 3

/// A baby's age in whole days — calendar days in local time, not elapsed hours,
/// so a baby born at 23:00 is one day old at 01:00 the next night.
public func ageInDays(birthDate: Int64, now: Int64) -> Int {
    let calendar = SproutFormat.calendar
    let birth = calendar.startOfDay(for: SproutFormat.date(from: birthDate))
    let today = calendar.startOfDay(for: SproutFormat.date(from: now))
    return calendar.dateComponents([.day], from: birth, to: today).day ?? 0
}

/// The typical window the baby is currently in, if any.
public func currentGrowthSpurt(ageDays: Int) -> GrowthSpurtWindow? {
    growthSpurtWindows.first { ageDays >= $0.startDay && ageDays <= $0.endDay }
}

/// The next window that has not started yet, if any remain.
public func nextGrowthSpurt(ageDays: Int) -> GrowthSpurtWindow? {
    growthSpurtWindows.first { ageDays < $0.startDay }
}

/// The next window, but only when it starts within `withinDays`.
public func upcomingGrowthSpurt(
    ageDays: Int,
    withinDays: Int = growthSpurtHeadsUpDays
) -> GrowthSpurtWindow? {
    guard let next = nextGrowthSpurt(ageDays: ageDays) else { return nil }
    return next.startDay - ageDays <= withinDays ? next : nil
}
