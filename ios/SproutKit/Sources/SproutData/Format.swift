import Foundation

/// Dates, durations and ages, as `ui/common/Format.kt` computes them.
///
/// Split in two on purpose. The arithmetic — which bucket a duration falls in,
/// how many weeks and days old a baby is — lives here as pure functions
/// returning *values*, so it is testable without a bundle and identical on both
/// phones. Turning those values into a sentence is the view's job, because that
/// is where the string catalog is.
///
/// The calendar is the device's, not UTC: "today" means the parent's today, and
/// a feed at 00:30 belongs to the night they are still awake in.
public enum SproutFormat {

    /// Injectable so tests are not at the mercy of the machine's zone. Every
    /// function below reads it rather than `Calendar.current` directly.
    public static var calendar: Calendar = .autoupdatingCurrent

    // MARK: - Days

    public static func date(from epochMillis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
    }

    public static func millis(from date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    /// Start of the day containing `epochMillis`, in local time.
    public static func startOfDay(_ epochMillis: Int64) -> Int64 {
        millis(from: calendar.startOfDay(for: date(from: epochMillis)))
    }

    /// The same wall-clock time one day later. Calendar arithmetic rather than
    /// adding 86 400 000, so the hour survives a daylight-saving change.
    public static func nextDay(_ epochMillis: Int64) -> Int64 {
        let next = calendar.date(byAdding: .day, value: 1, to: date(from: epochMillis))
        return millis(from: next ?? date(from: epochMillis))
    }

    public static func isSameDay(_ a: Int64, _ b: Int64) -> Bool {
        calendar.isDate(date(from: a), inSameDayAs: date(from: b))
    }

    /// The same date a year on. Calendar arithmetic, so it lands on the same day
    /// of the month rather than 365 days later.
    public static func plusOneYear(_ epochMillis: Int64) -> Int64 {
        let next = calendar.date(byAdding: .year, value: 1, to: date(from: epochMillis))
        return millis(from: next ?? date(from: epochMillis))
    }

    /// `epochMillis` moved to a given time of day, keeping its date.
    public static func settingTime(hour: Int, minute: Int, on epochMillis: Int64) -> Int64 {
        let point = calendar.date(
            bySettingHour: hour,
            minute: minute,
            second: 0,
            of: date(from: epochMillis)
        )
        return millis(from: point ?? date(from: epochMillis))
    }

    /// The wall-clock hour and minute of an instant, in local time.
    public static func hourAndMinute(_ epochMillis: Int64) -> (hour: Int, minute: Int) {
        let parts = calendar.dateComponents([.hour, .minute], from: date(from: epochMillis))
        return (parts.hour ?? 0, parts.minute ?? 0)
    }

    /// Which day a timestamp reads as, relative to `now`.
    public enum DayLabel: Equatable, Sendable {
        case today
        case yesterday
        case other(Int64)
    }

    public static func dayLabel(_ epochMillis: Int64, now: Int64) -> DayLabel {
        if isSameDay(epochMillis, now) { return .today }
        let yesterday = calendar.date(byAdding: .day, value: -1, to: date(from: now))
        if let yesterday, calendar.isDate(date(from: epochMillis), inSameDayAs: yesterday) {
            return .yesterday
        }
        return .other(epochMillis)
    }

    // MARK: - Greeting

    public enum Greeting: String, Equatable, Sendable {
        case morning, afternoon, evening, hello
    }

    public static func greeting(forHour hour: Int) -> Greeting {
        switch hour {
        case 5...11: return .morning
        case 12...17: return .afternoon
        case 18...21: return .evening
        default: return .hello
        }
    }

    public static func greeting(at epochMillis: Int64) -> Greeting {
        greeting(forHour: calendar.component(.hour, from: date(from: epochMillis)))
    }

    // MARK: - Elapsed time

    /// How long ago something was, in the bucket the copy is written for.
    public enum Relative: Equatable, Sendable {
        case justNow
        case minutes(Int)
        case hours(Int)
        case days(Int)
    }

    public static func relative(_ epochMillis: Int64, now: Int64) -> Relative {
        let deltaMinutes = (now - epochMillis) / 60_000
        switch deltaMinutes {
        case ..<1: return .justNow
        case ..<60: return .minutes(Int(deltaMinutes))
        case ..<(60 * 24): return .hours(Int(deltaMinutes / 60))
        default: return .days(Int(deltaMinutes / (60 * 24)))
        }
    }

    /// A span of time, in the bucket the copy is written for.
    public enum Duration: Equatable, Sendable {
        case seconds(Int)
        case minutes(Int)
        case hours(Int)
        case hoursMinutes(Int, Int)
    }

    public static func duration(millis: Int64) -> Duration {
        let magnitude = abs(millis)
        let totalMinutes = magnitude / 60_000
        let hours = Int(totalMinutes / 60)
        let minutes = Int(totalMinutes % 60)

        if totalMinutes == 0 { return .seconds(Int(magnitude / 1000)) }
        if hours == 0 { return .minutes(minutes) }
        if minutes == 0 { return .hours(hours) }
        return .hoursMinutes(hours, minutes)
    }

    /// A running timer as `M:SS`, or `H:MM:SS` past the hour.
    ///
    /// Digits only, so it is locale-neutral and needs no catalog — the one
    /// formatting function that returns a finished string.
    public static func clock(millis: Int64) -> String {
        let totalSeconds = abs(millis) / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    // MARK: - Age

    /// A baby's age, in the unit that reads naturally at that age.
    ///
    /// Days up to a fortnight, then weeks (with the odd days) up to two months,
    /// then months. The thresholds are Android's, and they matter: a parent of a
    /// nine-day-old counts in days, and one of a five-month-old does not.
    public enum Age: Equatable, Sendable {
        case notBornYet
        case days(Int)
        case weeks(Int)
        case weeksAndDays(Int, Int)
        case months(Int)
    }

    public static func age(birthDate: Int64, now: Int64) -> Age {
        let birthDay = calendar.startOfDay(for: date(from: birthDate))
        let today = calendar.startOfDay(for: date(from: now))

        if today < birthDay { return .notBornYet }

        let totalDays = calendar.dateComponents([.day], from: birthDay, to: today).day ?? 0

        if totalDays < 14 { return .days(totalDays) }
        if totalDays < 60 {
            let weeks = totalDays / 7
            let days = totalDays % 7
            return days == 0 ? .weeks(weeks) : .weeksAndDays(weeks, days)
        }
        let months = calendar.dateComponents([.month], from: birthDay, to: today).month ?? 0
        return .months(months)
    }
}
