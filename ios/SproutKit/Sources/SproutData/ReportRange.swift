import Foundation

/// How wide a report is, and where it sits. From `ui/report/ReportRange.kt`.
///
/// The presets are rolling windows ending today — "30 days" is the last thirty
/// days, not last month's calendar page — which is the arithmetic the Statistics
/// chips already do, so the paper and the screen cannot disagree.
public enum ReportPeriod: String, CaseIterable, Sendable {
    case week, month, quarter, sinceBirth, custom

    /// How many days the preset covers; `nil` for the two that are not a fixed
    /// width.
    public var days: Int? {
        switch self {
        case .week: return 7
        case .month: return 30
        case .quarter: return 90
        case .sinceBirth, .custom: return nil
        }
    }
}

/// The days a report covers, `from` through `to` inclusive.
public struct ReportRange: Equatable, Sendable {
    public let from: CalendarDay
    public let to: CalendarDay

    public init(from: CalendarDay, to: CalendarDay) {
        self.from = from
        self.to = to
    }

    /// How many calendar days that is; always at least one.
    public var dayCount: Int { max(from.days(until: to) + 1, 1) }

    /// Every day in the range, oldest first.
    public func days() -> [CalendarDay] {
        var result: [CalendarDay] = []
        var cursor = from
        while cursor <= to {
            result.append(cursor)
            cursor = cursor.adding(days: 1)
        }
        return result
    }

    /// The index of `day` in the range, or `nil` when it falls outside.
    public func index(of day: CalendarDay) -> Int? {
        guard day >= from, day <= to else { return nil }
        return from.days(until: day)
    }
}

/// The range a `period` means today, for a baby born on `birthDay`.
///
/// Two clamps do all the work, and both matter for the reason the Statistics
/// window has them (BDR-0008): **the range never ends after today**, because
/// tomorrow has not been lived, and **it never begins before the birth**, because
/// days the baby did not live are not days — a twelve-day-old asked for "30 days"
/// would otherwise have every average divided by eighteen days of nothing. The
/// report prints the range it actually covered rather than the one that was asked
/// for.
///
/// A birth date typed ahead of a due date still leaves a range of one day rather
/// than one that ends before it starts.
public func reportRange(
    period: ReportPeriod,
    today: CalendarDay,
    birthDay: CalendarDay?,
    customFrom: CalendarDay? = nil,
    customTo: CalendarDay? = nil
) -> ReportRange {
    let end: CalendarDay
    switch period {
    case .custom: end = min(customTo ?? today, today)
    default: end = today
    }

    let start: CalendarDay
    switch period {
    case .custom: start = customFrom ?? end
    case .sinceBirth: start = birthDay ?? end
    default: start = end.adding(days: -((period.days ?? 1) - 1))
    }

    var from = start
    if let birthDay, birthDay > start { from = birthDay }
    // A custom range typed back to front, or a birth date in the future: fall
    // back to the single day that is certainly real.
    if from > end { from = end }
    return ReportRange(from: from, to: end)
}
