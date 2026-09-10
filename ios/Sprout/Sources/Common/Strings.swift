import Foundation
import SproutData

/// Turning the values `SproutFormat` computes into the sentences the catalog
/// holds.
///
/// The split is deliberate (see `SproutFormat`): the arithmetic is in a library
/// with no bundle and no localisation, and this is the only place that knows
/// both. Keys are Android's, because the catalog is generated from Android's
/// resources — see `ios/tools/strings_from_android.py`.
enum Str {

    /// A plain key.
    static func t(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    /// A key with positional arguments.
    ///
    /// `String(format:locale:arguments:)` and not
    /// `localizedStringWithFormat(_:_:)`: Swift cannot spread one variadic into
    /// another, so passing `arguments` there hands the format a single *array*
    /// and prints something nobody wants. The array-taking overload is the one
    /// that works, and passing a locale is what applies a plural variation —
    /// several of these keys have one.
    static func t(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: .current, arguments: arguments)
    }
}

// MARK: - Format values as sentences

extension SproutFormat.DayLabel {
    var text: String {
        switch self {
        case .today: return Str.t("day_today")
        case .yesterday: return Str.t("day_yesterday")
        case .other(let millis): return SproutDateStyle.date(millis)
        }
    }
}

extension SproutFormat.Greeting {
    var text: String {
        switch self {
        case .morning: return Str.t("greeting_morning")
        case .afternoon: return Str.t("greeting_afternoon")
        case .evening: return Str.t("greeting_evening")
        case .hello: return Str.t("greeting_hello")
        }
    }
}

extension SproutFormat.Relative {
    var text: String {
        switch self {
        case .justNow: return Str.t("relative_just_now")
        case .minutes(let value): return Str.t("relative_minutes", value)
        case .hours(let value): return Str.t("relative_hours", value)
        case .days(let value): return Str.t("relative_days", value)
        }
    }
}

extension SproutFormat.Duration {
    var text: String {
        switch self {
        case .seconds(let value): return Str.t("duration_seconds", value)
        case .minutes(let value): return Str.t("duration_minutes", value)
        case .hours(let value): return Str.t("duration_hours", value)
        case .hoursMinutes(let hours, let minutes):
            return Str.t("duration_hours_minutes", hours, minutes)
        }
    }
}

extension SproutFormat.Age {
    var text: String {
        switch self {
        case .notBornYet:
            return Str.t("age_not_born")
        case .days(let days):
            return Str.t("age_days", days)
        case .weeks(let weeks):
            return Str.t("age_weeks", weeks)
        case .weeksAndDays(let weeks, let days):
            // Two plurals composed into one sentence, exactly as Android does
            // it — the joining string is itself translated, because word order
            // is not ours to assume.
            return Str.t("age_weeks_days", Str.t("age_weeks", weeks), Str.t("age_days", days))
        case .months(let months):
            return Str.t("age_months", months)
        }
    }
}

// MARK: - Dates and times

/// Absolute dates and times.
///
/// Android reads its patterns out of the string resources (`fmt_date`,
/// `fmt_datetime`) so a translator can reorder them. Those keys are in the
/// catalog too, but `Date.FormatStyle` already localises order, separators and
/// month names from the user's settings — which is more correct than a pattern
/// per language, and is what an iOS user expects to see.
enum SproutDateStyle {

    static func date(_ millis: Int64) -> String {
        SproutFormat.date(from: millis)
            .formatted(.dateTime.day().month(.abbreviated).year())
    }

    static func dateTime(_ millis: Int64) -> String {
        SproutFormat.date(from: millis)
            .formatted(.dateTime.day().month(.abbreviated).hour().minute())
    }

    /// Time of day. 24-hour on Android because the pattern says so; here it
    /// follows the phone's own setting, which a French parent has already
    /// chosen once.
    static func time(_ millis: Int64) -> String {
        SproutFormat.date(from: millis).formatted(.dateTime.hour().minute())
    }

    static func dayAndTime(_ millis: Int64, now: Int64) -> String {
        "\(SproutFormat.dayLabel(millis, now: now).text) · \(time(millis))"
    }
}

// MARK: - Domain labels

extension FeedType {
    var label: String {
        switch self {
        case .BREAST: return Str.t("feed_type_breast")
        case .BOTTLE: return Str.t("feed_type_bottle")
        case .SOLID: return Str.t("feed_type_solid")
        }
    }
}

extension BreastSide {
    var label: String {
        switch self {
        case .LEFT: return Str.t("side_left")
        case .RIGHT: return Str.t("side_right")
        case .BOTH: return Str.t("side_both")
        }
    }
}

extension SleepPosition {
    var label: String {
        switch self {
        case .BACK: return Str.t("sleep_position_back")
        case .SIDE: return Str.t("sleep_position_side")
        case .BELLY: return Str.t("sleep_position_belly")
        }
    }
}

extension SleepPlace {
    var label: String {
        switch self {
        case .OWN_BED: return Str.t("sleep_place_own_bed")
        case .BEDSIDE_COT: return Str.t("sleep_place_bedside_cot")
        case .PARENTS_BED: return Str.t("sleep_place_parents_bed")
        case .ON_A_PARENT: return Str.t("sleep_place_on_a_parent")
        case .AT_BREAST: return Str.t("sleep_place_at_breast")
        case .OTHER: return Str.t("sleep_place_other")
        }
    }
}

extension MilkStorage {
    var label: String {
        switch self {
        case .FRIDGE: return Str.t("storage_fridge")
        case .FREEZER: return Str.t("storage_freezer")
        case .ROOM: return Str.t("storage_room")
        case .USED: return Str.t("storage_used")
        }
    }
}

// MARK: - The parent's own labels

/// The mood scale, from `ui/common/HealthLabels.kt`.
///
/// Emoji rather than words, and the same five on both platforms: a face is read
/// at a glance and needs no translation, which matters for the one screen a
/// parent uses while too tired to read.
func moodEmoji(_ mood: Int) -> String {
    switch mood {
    case 1: return "😢"
    case 2: return "🙁"
    case 3: return "😐"
    case 4: return "🙂"
    default: return "😄"
    }
}

extension Bleeding {
    var label: String {
        switch self {
        case .NONE: return Str.t("bleeding_none")
        case .LIGHT: return Str.t("bleeding_light")
        case .MODERATE: return Str.t("bleeding_moderate")
        case .HEAVY: return Str.t("bleeding_heavy")
        }
    }
}

extension BreastState {
    var label: String {
        switch self {
        case .NORMAL: return Str.t("breast_normal")
        case .TENDER: return Str.t("breast_tender")
        case .ENGORGED: return Str.t("breast_engorged")
        case .PAINFUL: return Str.t("breast_painful")
        }
    }
}

extension Recovery {
    var label: String {
        switch self {
        case .GREAT: return Str.t("recovery_great")
        case .GOOD: return Str.t("recovery_good")
        case .SORE: return Str.t("recovery_sore")
        case .PAINFUL: return Str.t("recovery_painful")
        }
    }
}

extension DeliveryType {
    var label: String {
        switch self {
        case .VAGINAL: return Str.t("delivery_vaginal")
        case .CESAREAN: return Str.t("delivery_cesarean")
        }
    }
}

/// The healing question, worded for the delivery — and worded generically when
/// the parent did not say, which is a real answer and not a gap. Asking a
/// caesarean question of someone who had a vaginal birth is the kind of small
/// wrongness this screen cannot afford.
func healingQuestion(_ deliveryType: DeliveryType?) -> String {
    switch deliveryType {
    case .CESAREAN: return Str.t("healing_q_cesarean")
    case .VAGINAL: return Str.t("healing_q_vaginal")
    case nil: return Str.t("healing_q_generic")
    }
}

/// The same question as a short field label.
func healingFieldLabel(_ deliveryType: DeliveryType?) -> String {
    switch deliveryType {
    case .CESAREAN: return Str.t("healing_label_cesarean")
    case .VAGINAL: return Str.t("healing_label_vaginal")
    case nil: return Str.t("healing_label_generic")
    }
}
