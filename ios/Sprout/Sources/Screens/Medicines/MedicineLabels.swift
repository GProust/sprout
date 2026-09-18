import SproutData
import SwiftUI

/*
 * How a medicine's state is drawn and worded — the mirror of
 * `ui/medicines/MedicineLabels.kt`.
 *
 * Their own file because two screens say the same things about the same
 * medicine: the as-needed list, and the dashboard card that tells a parent a
 * wait is running without making them go and look (BDR-16). Two copies of
 * "Too soon — 4 h to wait" would eventually disagree, and the screen that was
 * wrong would be the one nobody was looking at.
 */

extension MedicineLevel {
    /// The three state colours.
    ///
    /// Fixed values, and the warm middle is the one the statistics already use —
    /// chosen by running a colour-vision check rather than by eye.
    var color: Color {
        switch self {
        case .tooSoon: return Color(light: 0xBA1A1A, dark: 0xFFB4AB)
        case .soonerThanIdeal: return Color(light: 0xB26A2B, dark: 0xCF8949)
        case .ready: return Color(light: 0x1B5E3F, dark: 0x7FD0A6)
        }
    }

    /// A different shape per state, so the three differ by more than their hue.
    var symbol: String {
        switch self {
        case .tooSoon: return "hourglass"
        case .soonerThanIdeal: return "clock"
        case .ready: return "checkmark.circle.fill"
        }
    }
}

/// The sentence the card leads with — the state in words, never colour alone.
func stateSentence(_ readiness: MedicineReadiness, now: Int64) -> String {
    switch readiness.level {
    case .tooSoon:
        let left = SproutFormat.duration(millis: (readiness.nextAllowedAt ?? now) - now).text
        switch readiness.reason {
        case .dailyMaximum: return Str.t("medicine_state_daily_max", left)
        // Named apart from the dose count, because "you have used the day's
        // 1.5 cm" and "that would be the seventh today" are two different
        // things to have run out of.
        case .dailyAmount: return Str.t("medicine_state_daily_amount", left)
        default: return Str.t("medicine_state_too_soon", left)
        }
    case .soonerThanIdeal:
        let left = SproutFormat.duration(millis: (readiness.comfortableAt ?? now) - now).text
        return Str.t("medicine_state_early", left)
    case .ready:
        return Str.t("medicine_state_ready")
    }
}

/// The state in as few words as a line can carry: "4 h 12 m to wait", "1 h
/// early", or nothing at all once the full wait has passed.
///
/// The dashboard's line has room for a name and a number and no more, and a bare
/// duration would not say whether it is a wait or a head start — so the number
/// keeps the one word that says which. Green has no number, because there is
/// nothing left to count: the name's colour and the tick beside it are the whole
/// message, and ``stateSentence(_:now:)`` is still what VoiceOver is given.
func shortState(_ readiness: MedicineReadiness, now: Int64) -> String? {
    switch readiness.level {
    case .tooSoon:
        let left = SproutFormat.duration(millis: (readiness.nextAllowedAt ?? now) - now).text
        return Str.t("medicine_short_wait", left)
    case .soonerThanIdeal:
        let left = SproutFormat.duration(millis: (readiness.comfortableAt ?? now) - now).text
        return Str.t("medicine_short_early", left)
    case .ready:
        return nil
    }
}

/// "Last dose 03:20 · 2 of 4 in the last 24 h · 0.5 of 1.5 cm", or that it has
/// never been given.
///
/// The quantity is only there when the parent gave a daily one to count
/// against: a medicine measured in whole doses says nothing about millilitres,
/// and a running total nobody set a limit for is a number with no question
/// behind it.
func lastDoseLine(_ readiness: MedicineReadiness) -> String {
    guard let last = readiness.lastDoseAt else { return Str.t("medicine_never_given") }
    let count = readiness.maxPerDay.map {
        Str.t("medicine_day_count", readiness.dosesInLastDay, $0)
    } ?? Str.t("medicine_day_count_plain", readiness.dosesInLastDay)
    let line = Str.t(
        "treatment_schedule_summary",
        Str.t("medicine_last_dose", SproutDateStyle.dateTime(last)),
        count
    )
    guard let max = readiness.maxAmountPerDay else { return line }
    return Str.t(
        "treatment_schedule_summary",
        line,
        Str.t(
            "medicine_day_amount",
            SproutFormat.decimal(readiness.amountInLastDay),
            SproutFormat.decimal(max),
            readiness.unit ?? ""
        )
    )
}

/// "0.25 cm" — an amount in the medicine's own unit, or nil when it has none.
func amountLabel(_ amount: Double?, unit: String?) -> String? {
    guard let amount else { return nil }
    let value = SproutFormat.decimal(amount)
    guard let unit, !unit.trimmingCharacters(in: .whitespaces).isEmpty else { return value }
    return Str.t("medicine_amount_value", value, unit)
}

/// "Every 6 h to 8 h", "Every 6 h" when only a minimum was set, or "No set gap"
/// for a medicine whose leaflet gave none at all (BDR-18).
func intervalSummary(_ medicine: Medicine) -> String {
    guard medicine.minIntervalMinutes > 0 else { return Str.t("medicine_no_gap") }
    let min = SproutFormat.duration(millis: Int64(medicine.minIntervalMinutes) * 60_000).text
    guard let comfort = medicine.comfortIntervalMinutes,
          comfort > medicine.minIntervalMinutes
    else { return Str.t("medicine_every", min) }
    return Str.t(
        "medicine_every_range",
        min,
        SproutFormat.duration(millis: Int64(comfort) * 60_000).text
    )
}
