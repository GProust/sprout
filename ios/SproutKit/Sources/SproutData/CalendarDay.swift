import Foundation

/// A calendar day, with no time and no zone — Java's `LocalDate`, which the
/// statistics are written in terms of on the other side.
///
/// `Date` will not do here. The whole of `DailyStats` asks "which day did this
/// land on" and "how much of this night fell inside that day", and an instant
/// cannot answer either without being told a calendar every time. This can be a
/// dictionary key, compared, and stepped a day at a time — and the stepping goes
/// through `Calendar`, so the short and long days either side of a
/// daylight-saving change come out at 23 and 25 hours, as they were lived.
public struct CalendarDay: Hashable, Comparable, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    public init(millis: Int64, calendar: Calendar = SproutFormat.calendar) {
        let parts = calendar.dateComponents(
            [.year, .month, .day],
            from: SproutFormat.date(from: millis)
        )
        self.init(year: parts.year ?? 0, month: parts.month ?? 0, day: parts.day ?? 0)
    }

    /// Midnight at the start of this day, in local time.
    public func startMillis(calendar: Calendar = SproutFormat.calendar) -> Int64 {
        let components = DateComponents(year: year, month: month, day: day)
        guard let date = calendar.date(from: components) else { return 0 }
        return SproutFormat.millis(from: calendar.startOfDay(for: date))
    }

    /// `count` days later — through the calendar, not by adding 86 400 000.
    public func adding(days count: Int, calendar: Calendar = SproutFormat.calendar) -> CalendarDay {
        let start = SproutFormat.date(from: startMillis(calendar: calendar))
        guard let moved = calendar.date(byAdding: .day, value: count, to: start) else { return self }
        return CalendarDay(millis: SproutFormat.millis(from: moved), calendar: calendar)
    }

    /// Whole days from `self` to `other`; negative when `other` is earlier.
    public func days(until other: CalendarDay, calendar: Calendar = SproutFormat.calendar) -> Int {
        let from = SproutFormat.date(from: startMillis(calendar: calendar))
        let to = SproutFormat.date(from: other.startMillis(calendar: calendar))
        return calendar.dateComponents([.day], from: from, to: to).day ?? 0
    }

    public static func < (lhs: CalendarDay, rhs: CalendarDay) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }
}
