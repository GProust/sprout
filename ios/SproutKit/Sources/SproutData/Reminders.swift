import Foundation

/// When a reminder is due, and whether it is wanted at all.
/// From `notifications/FeedingReminders.kt` and `TreatmentReminders.kt`.
///
/// **Arithmetic only, no scheduling.** Deciding *when* is the same question on
/// both phones and belongs where it can be tested without a device; handing that
/// moment to the operating system is a per-platform job, and the two platforms
/// answer it very differently (ADR-0019).

// MARK: - Feeding

/// The moment a baby becomes "overdue" given its last feed.
public func feedingReminderTrigger(lastFeedTime: Int64, intervalMinutes: Int) -> Int64 {
    lastFeedTime + Int64(intervalMinutes) * 60_000
}

/// Whether `now` is at least `intervalMinutes` past `lastFeedTime`.
public func feedingReminderOverdue(
    lastFeedTime: Int64,
    now: Int64,
    intervalMinutes: Int
) -> Bool {
    now - lastFeedTime >= Int64(intervalMinutes) * 60_000
}

/// The feeding-reminder behaviour in force for a baby once its override is
/// resolved.
public struct EffectiveFeedingReminder: Equatable, Sendable {
    public let enabled: Bool
    public let intervalMinutes: Int

    public init(enabled: Bool, intervalMinutes: Int) {
        self.enabled = enabled
        self.intervalMinutes = intervalMinutes
    }
}

/// The device-wide default, and the choices Settings offers.
///
/// Kept with the ordinary settings, which a backup carries (ADR-0018): a parent
/// who moves phones should not have to set their feeding gap again.
public enum FeedingReminderSettings {

    /// Default maximum gap between feeds: three hours.
    public static let defaultIntervalMinutes = 180

    /// Selectable limits offered in Settings, in minutes (1h30 … 5h).
    public static let intervalChoices = [90, 120, 150, 180, 210, 240, 300]

    static let enabledKey = "feeding_reminders_enabled"
    static let intervalKey = "feeding_max_interval_minutes"

    /// **Off by default.** A reminder nobody asked for, about a baby who may be
    /// asleep, is the one notification this app must not send unprompted.
    public static func isEnabled(_ store: any DeviceLocalStore) -> Bool {
        store.bool(forKey: enabledKey, default: false)
    }

    public static func setEnabled(_ enabled: Bool, in store: any DeviceLocalStore) {
        store.setBool(enabled, forKey: enabledKey)
    }

    public static func intervalMinutes(_ store: any DeviceLocalStore) -> Int {
        store.int64(forKey: intervalKey).map(Int.init) ?? defaultIntervalMinutes
    }

    public static func setIntervalMinutes(_ minutes: Int, in store: any DeviceLocalStore) {
        store.setInt64(Int64(minutes), forKey: intervalKey)
    }
}

/// Resolves a baby's effective feeding reminder: **each field independently** is
/// the baby's own override when set, otherwise the device-wide default.
///
/// Independently, and that is the point — a parent who sets a shorter gap for a
/// newborn twin has not also said "on" for a baby whose reminders they turned
/// off, and vice versa.
public func effectiveFeedingReminder(
    baby: Baby,
    settings store: any DeviceLocalStore
) -> EffectiveFeedingReminder {
    EffectiveFeedingReminder(
        enabled: baby.feedingReminderEnabled ?? FeedingReminderSettings.isEnabled(store),
        intervalMinutes: baby.feedingReminderIntervalMinutes
            ?? FeedingReminderSettings.intervalMinutes(store)
    )
}

// MARK: - Treatments

/// The next moment `treatment` is due at `minuteOfDay`, strictly after `after`;
/// `nil` once the course's end date has passed.
///
/// Days rather than milliseconds throughout, because "every second day at 09:00"
/// means the same wall-clock time on the right dates — adding 48 hours across a
/// daylight-saving change would drift the dose by an hour and keep drifting.
/// The calendar is `SproutFormat`'s, as everywhere else in this module — a
/// second one passed in here would disagree with the one `settingTime` uses
/// below, and the disagreement would only show on the days that matter.
public func nextTreatmentTrigger(
    treatment: Treatment,
    minuteOfDay: Int,
    after: Int64
) -> Int64? {
    let startDay = CalendarDay(millis: treatment.startDate)
    let afterDay = CalendarDay(millis: after)
    let interval = max(1, treatment.intervalDays)

    // The first day of the dosing grid that is on or after both the course's
    // start and the day we are asking from.
    var day = max(startDay, afterDay)
    let elapsed = startDay.days(until: day)
    let remainder = ((elapsed % interval) + interval) % interval
    if remainder != 0 { day = day.adding(days: interval - remainder) }

    var trigger = moment(day, minuteOfDay: minuteOfDay)
    // The time of day may already have passed today; step forward a whole
    // interval at a time rather than to tomorrow, or an every-third-day course
    // would start reminding daily.
    while trigger <= after {
        day = day.adding(days: interval)
        trigger = moment(day, minuteOfDay: minuteOfDay)
    }

    if let endDate = treatment.endDate {
        let endDay = CalendarDay(millis: endDate)
        if day > endDay { return nil }
    }
    return trigger
}

private func moment(_ day: CalendarDay, minuteOfDay: Int) -> Int64 {
    SproutFormat.settingTime(
        hour: minuteOfDay / 60,
        minute: minuteOfDay % 60,
        on: day.startMillis()
    )
}

// MARK: - Growth spurts

/// The time of day a growth-spurt heads-up fires: nine in the morning.
///
/// Gentle by design. This is information, not an alarm, and it must never be the
/// thing that wakes a household — which is also why there is exactly one per
/// window rather than one a day through it.
public let growthSpurtNotifyMinuteOfDay = 9 * 60

/// The next growth-spurt heads-up for a baby: 09:00 on the first day of the next
/// typical window that starts after `now`. `nil` once the baby has outgrown the
/// known windows.
public func nextGrowthSpurtTrigger(birthDate: Int64, now: Int64) -> Int64? {
    let birthDay = CalendarDay(millis: birthDate)
    for window in growthSpurtWindows {
        let day = birthDay.adding(days: window.startDay)
        let trigger = moment(day, minuteOfDay: growthSpurtNotifyMinuteOfDay)
        if trigger > now { return trigger }
    }
    return nil
}

/// Whether growth-spurt heads-ups are wanted on this device. Opt-in, off until
/// asked for, like every other notification here.
public enum GrowthSpurtSettings {

    static let enabledKey = "growth_spurt_alerts_enabled"

    public static func isEnabled(_ store: any DeviceLocalStore) -> Bool {
        store.bool(forKey: enabledKey, default: false)
    }

    public static func setEnabled(_ enabled: Bool, in store: any DeviceLocalStore) {
        store.setBool(enabled, forKey: enabledKey)
    }
}
