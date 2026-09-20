import Foundation

/// Whether an as-needed medicine may be given yet, worked out from the doses
/// that were actually logged (BDR-15). From `data/MedicineReadiness.kt`.
///
/// **Arithmetic only.** No sentence, no colour, no symbol — those are the
/// screen's job, and this has to be testable without a device. The rules are
/// Android's, written twice and checked against the same cases.
///
/// Nothing here is a medical judgement. Every number it reads
/// (``Medicine/minIntervalMinutes``, ``Medicine/comfortIntervalMinutes``,
/// ``Medicine/maxPerDay``) was typed in by the parent from a prescriber or a
/// leaflet; this counts hours and hands the figures back.

/// How long a daily maximum is counted over.
public let medicineDayMs: Int64 = 24 * 60 * 60 * 1000

/// The three states, in the order a wait passes through them.
///
/// The names say what they mean rather than what colour they are drawn in: a
/// screen that only knew "amber" could not write the sentence that goes with
/// it, and the colour is never the only channel this is shown through.
public enum MedicineLevel: String, Sendable, Equatable {
    /// Too soon: the minimum wait has not elapsed, or the daily maximum is spent.
    case tooSoon
    /// Past the minimum, not yet past the comfortable interval. Allowed, sooner
    /// than ideal.
    case soonerThanIdeal
    /// The full wait has passed — or there was only ever one boundary, and it has.
    case ready
}

/// Why a medicine is ``MedicineLevel/tooSoon``; `nil` in every other state.
public enum TooSoonReason: String, Sendable, Equatable {
    /// The minimum gap between doses has not elapsed.
    case interval
    /// The parent's daily maximum has been reached; the interval alone would
    /// allow it.
    case dailyMaximum
    /// The day's allowed *quantity* is used up — 1.5 cm of gel, not six of six
    /// doses. A second ceiling because a leaflet often gives both, and three
    /// generous applications can reach the amount before the count (BDR-18).
    case dailyAmount
}

/// How close two amounts have to be to count as equal.
///
/// Amounts are the parent's own decimals and are added up in binary floating
/// point, where 0.3 three times is not 0.9. Without a tolerance a day that has
/// exactly reached its limit could read as a hair under it — and since nothing
/// here blocks a dose anyway, the honest thing is for the arithmetic to agree
/// with the parent's own sum rather than with the last bit of a Double.
private let medicineAmountTolerance = 1e-6

/// What the screen draws, and what a reminder is scheduled from.
public struct MedicineReadiness: Sendable, Equatable {
    public let level: MedicineLevel
    /// Why, when ``level`` is ``MedicineLevel/tooSoon``.
    public let reason: TooSoonReason?
    /// When the last dose was, or `nil` if it has never been given.
    public let lastDoseAt: Int64?
    /// The moment ``level`` stops being ``MedicineLevel/tooSoon``; `nil` when it
    /// already is not.
    public let nextAllowedAt: Int64?
    /// The moment the comfortable interval elapses; `nil` when the medicine has
    /// none, or it already has.
    public let comfortableAt: Int64?
    /// How many doses were given in the last 24 hours.
    public let dosesInLastDay: Int
    /// The parent's own daily maximum, echoed back for the screen to draw the
    /// count against; `nil` when they set none.
    public let maxPerDay: Int?
    /// How much was used in the last 24 hours, in the medicine's own unit. Zero
    /// when nothing was recorded, which is not the same as nothing being used.
    public let amountInLastDay: Double
    /// The parent's own daily quantity, echoed back beside ``amountInLastDay``;
    /// `nil` when they set none.
    public let maxAmountPerDay: Double?
    /// The medicine's own unit for the two amounts, echoed back so the screen
    /// does not have to carry the medicine as well.
    public let unit: String?

    public init(
        level: MedicineLevel,
        reason: TooSoonReason? = nil,
        lastDoseAt: Int64? = nil,
        nextAllowedAt: Int64? = nil,
        comfortableAt: Int64? = nil,
        dosesInLastDay: Int = 0,
        maxPerDay: Int? = nil,
        amountInLastDay: Double = 0,
        maxAmountPerDay: Double? = nil,
        unit: String? = nil
    ) {
        self.level = level
        self.reason = reason
        self.lastDoseAt = lastDoseAt
        self.nextAllowedAt = nextAllowedAt
        self.comfortableAt = comfortableAt
        self.dosesInLastDay = dosesInLastDay
        self.maxPerDay = maxPerDay
        self.amountInLastDay = amountInLastDay
        self.maxAmountPerDay = maxAmountPerDay
        self.unit = unit
    }
}

/// Works out where `medicine` stands at `now`, given `doses`.
///
/// `doses` may be in any order and may contain doses of other medicines or from
/// outside the window — they are filtered here rather than at every call site,
/// so a screen holding one list of every dose can ask about each medicine in
/// turn without slicing it up first.
///
/// A medicine that has never been given is ``MedicineLevel/ready``: no wait is
/// running, so there is nothing to wait for. That is the state the screen should
/// open in for a medicine added a moment ago, and the alternative — red, with an
/// unknown countdown — would be a lie.
public func medicineReadiness(
    medicine: Medicine,
    doses: [MedicineDose],
    now: Int64
) -> MedicineReadiness {
    let mine = doses
        .filter { $0.medicineUid == medicine.uid && $0.deletedAt == nil }
        .sorted { $0.time > $1.time }

    // Doses in the future are the parent correcting a time, or two phones whose
    // clocks disagree. They still count as given — a dose logged at 21:05 by a
    // phone five minutes fast is the dose that was just given, not one to
    // ignore — so the wait runs from the latest of them either way.
    guard let lastDoseAt = mine.first?.time else {
        return MedicineReadiness(
            level: .ready,
            maxPerDay: medicine.maxPerDay,
            maxAmountPerDay: medicine.maxAmountPerDay,
            unit: medicine.doseUnit
        )
    }

    let minGapMs = Int64(max(0, medicine.minIntervalMinutes)) * 60_000
    let intervalEndsAt = lastDoseAt + minGapMs

    // A comfortable interval shorter than the minimum is a typo, not a second
    // boundary. Taking the larger keeps the two in the order the states assume
    // rather than producing a middle band that ends before it begins.
    let comfortEndsAt = medicine.comfortIntervalMinutes.map { minutes in
        lastDoseAt + max(Int64(max(0, minutes)) * 60_000, minGapMs)
    }

    // The daily maximum, counted over a rolling 24 hours rather than a calendar
    // day: "no more than four in a day" is about the last day, and a calendar
    // reset would allow four at 23:00 and four more at 00:30.
    let windowStart = now - medicineDayMs
    let inWindow = mine.filter { $0.time > windowStart && $0.time <= now }
    let cap: Int? = medicine.maxPerDay.flatMap { limit -> Int? in limit > 0 ? limit : nil }
    // `inWindow` is newest-first, so the cap-th most recent dose is the one that
    // has to age out of the window before another is allowed.
    let capReachedUntil: Int64? = cap.flatMap { limit -> Int64? in
        inWindow.count >= limit ? inWindow[limit - 1].time + medicineDayMs : nil
    }

    // The day's quantity, the same rolling window and the same shape as the
    // count. Doses that recorded no amount add nothing — the arithmetic cannot
    // total what was never typed, and guessing a size for them would be the app
    // inventing a figure the parent never gave.
    let amountUsed = inWindow.reduce(0.0) { $0 + ($1.amount ?? 0) }
    let amountCap: Double? = medicine.maxAmountPerDay.flatMap { max -> Double? in
        max > 0 ? max : nil
    }
    let amountReachedUntil: Int64? = amountCap.flatMap { amountFreeAt(inWindow, max: $0) }

    let blockedUntil: Int64? = [
        intervalEndsAt.after(now),
        capReachedUntil?.after(now),
        amountReachedUntil?.after(now),
    ].compactMap { $0 }.max()

    if let blockedUntil {
        return MedicineReadiness(
            // The interval is named only when it is the thing still running:
            // once it has elapsed, "the daily maximum" is the honest answer to
            // why the screen is still red.
            level: .tooSoon,
            // The interval keeps its precedence: while it is running it is what
            // the parent is waiting on first, whichever ceiling is also spent.
            reason: {
                if intervalEndsAt > now { return .interval }
                if let capReachedUntil, capReachedUntil > now { return .dailyMaximum }
                return .dailyAmount
            }(),
            lastDoseAt: lastDoseAt,
            nextAllowedAt: blockedUntil,
            comfortableAt: comfortEndsAt?.after(now),
            dosesInLastDay: inWindow.count,
            maxPerDay: medicine.maxPerDay,
            amountInLastDay: amountUsed,
            maxAmountPerDay: medicine.maxAmountPerDay,
            unit: medicine.doseUnit
        )
    }

    let stillEarly = comfortEndsAt.map { $0 > now } ?? false
    return MedicineReadiness(
        level: stillEarly ? .soonerThanIdeal : .ready,
        lastDoseAt: lastDoseAt,
        comfortableAt: comfortEndsAt?.after(now),
        dosesInLastDay: inWindow.count,
        maxPerDay: medicine.maxPerDay,
        amountInLastDay: amountUsed,
        maxAmountPerDay: medicine.maxAmountPerDay,
        unit: medicine.doseUnit
    )
}

/// When the day's quantity drops back below `max`, or `nil` while it never
/// reached it.
///
/// The same rule the dose count uses, applied to a total rather than a tally:
/// doses age out of the rolling window oldest first, and the moment the ones
/// still inside it add up to less than the limit is the moment there is room
/// again. Doses that recorded no amount contribute nothing on the way in and
/// free nothing on the way out.
private func amountFreeAt(_ inWindow: [MedicineDose], max: Double) -> Int64? {
    let oldestFirst = inWindow.sorted { $0.time < $1.time }
    var remaining = oldestFirst.reduce(0.0) { $0 + ($1.amount ?? 0) }
    if remaining < max - medicineAmountTolerance { return nil }

    for dose in oldestFirst {
        remaining -= dose.amount ?? 0
        if remaining < max - medicineAmountTolerance { return dose.time + medicineDayMs }
    }
    // One dose was the whole day's allowance on its own: nothing is free until
    // the last of them has aged out.
    return oldestFirst.last.map { $0.time + medicineDayMs }
}

/// One medicine the dashboard should mention, and where it stands.
///
/// The readiness is carried rather than recomputed by the screen, so the card
/// and the medicines screen cannot disagree about the same medicine at the same
/// instant.
public struct MedicineWatch: Identifiable, Sendable, Equatable {
    public let medicine: Medicine
    public let readiness: MedicineReadiness

    /// The medicine's `uid`, which is stable across a reorder — an index would
    /// make SwiftUI reuse the wrong row when a wait elapses and the list sorts
    /// itself differently.
    public var id: String { medicine.uid }

    public init(medicine: Medicine, readiness: MedicineReadiness) {
        self.medicine = medicine
        self.readiness = readiness
    }
}

/// The medicines worth a line on the dashboard (BDR-16).
///
/// A medicine earns its place when **a wait is running** — it cannot be given
/// yet, or it can but sooner than ideal — or when it was **given within the last
/// day and the wait has since passed**. That last case is the one a parent is
/// actually waiting for, and the dashboard is where they should not have to go
/// looking for it.
///
/// A medicine that has never been given, or whose last dose is older than the
/// window, is left off: it is set up rather than in play, and the screen that
/// lists every medicine is one tap away. That is what keeps this card absent
/// from the dashboard of a household that is not in the middle of anything.
///
/// Ordered by **how freely it may be given**: the full wait passed, then allowed
/// but sooner than ideal, then not yet — with the soonest wait first inside each
/// group and the name breaking the last tie, so the list does not reshuffle under
/// a parent who is reading it.
///
/// Green above amber, and not merely "givable first". Both may be given, but one
/// of them is the dose the parent was told to give and the other is the dose they
/// are allowed to give early — so the medicine that needs no second thought is
/// the one at the top. It is an ordering, not a recommendation: the amber line
/// says what it says, and its button works exactly as well (BDR-15).
///
/// `dismissed` is what *Dismiss* on the card put away, as medicine uid to the
/// dose it was dismissed against. A dismissal therefore expires by itself: give
/// another dose and the medicine's last dose is no longer the one that was put
/// away, so it comes back. There is nothing to clear and nothing to leak — a
/// medicine that is deleted takes its entry out of use with it.
public func medicinesNeedingAttention(
    medicines: [Medicine],
    doses: [MedicineDose],
    now: Int64,
    dismissed: [String: Int64] = [:]
) -> [MedicineWatch] {
    medicines
        .filter { $0.active && $0.deletedAt == nil }
        .map { MedicineWatch(medicine: $0, readiness: medicineReadiness(medicine: $0, doses: doses, now: now)) }
        // Never given is not "in play": there is no wait to report and nothing
        // has happened that the dashboard needs to carry.
        .filter { $0.readiness.lastDoseAt != nil }
        .filter { $0.readiness.level != .ready || $0.readiness.dosesInLastDay > 0 }
        .filter { dismissed[$0.medicine.uid] != $0.readiness.lastDoseAt }
        .sorted { left, right in
            if left.readiness.level.givingOrder != right.readiness.level.givingOrder {
                return left.readiness.level.givingOrder < right.readiness.level.givingOrder
            }
            // Then the moment this group's own wait ends — `nextAllowedAt` for a
            // medicine still too soon, `comfortableAt` for one that is early,
            // and neither for one that is simply ready. Both are nil in that
            // last case, so the fallback leaves the name to order them.
            let a = left.readiness.nextAllowedAt ?? left.readiness.comfortableAt ?? 0
            let b = right.readiness.nextAllowedAt ?? right.readiness.comfortableAt ?? 0
            if a != b { return a < b }
            return left.medicine.name < right.medicine.name
        }
}

private extension MedicineLevel {
    /// Where a state sits when the dashboard asks "what may I give?".
    ///
    /// Written out rather than taken from the case order, which runs the other
    /// way: the enum is declared in the order a *wait* passes through, and this
    /// is the order a *parent* wants to read.
    var givingOrder: Int {
        switch self {
        case .ready: return 0
        case .soonerThanIdeal: return 1
        case .tooSoon: return 2
        }
    }
}

/// When to tell the parent that `medicine` can be given again, or `nil` when it
/// wants no reminder, is already there, or has never been given.
///
/// The boundary is the parent's own choice (``Medicine/remindAtComfort``), and
/// the daily maximum can push it later than either interval — saying a medicine
/// is available while the day's allowance is spent would be the app getting its
/// own arithmetic wrong out loud.
///
/// Strictly after `now`: a moment that has already passed is not a reminder, and
/// on iOS there is no occasion to deliver a late one except the app being opened
/// (ADR-0019).
public func nextMedicineReminder(
    medicine: Medicine,
    doses: [MedicineDose],
    now: Int64
) -> Int64? {
    guard medicine.remindWhenDue, medicine.active, medicine.deletedAt == nil else { return nil }
    let readiness = medicineReadiness(medicine: medicine, doses: doses, now: now)
    guard readiness.lastDoseAt != nil else { return nil }

    // Falls back to the minimum when there is no comfortable interval: the
    // switch is then about *whether* to remind, and there is one boundary to
    // remind at.
    let wanted = medicine.remindAtComfort
        ? (readiness.comfortableAt ?? readiness.nextAllowedAt)
        : readiness.nextAllowedAt

    let trigger = [wanted, readiness.nextAllowedAt].compactMap { $0 }.max()
    return trigger?.after(now)
}

private extension Int64 {
    /// This moment, but only when it is still ahead — `nil` once it has passed.
    ///
    /// A named helper rather than a ternary at each of the five places that want
    /// it: `$0 > now ? $0 : nil` inside a `flatMap` leaves Swift inferring the
    /// closure's return type from a `nil`, which it can do and occasionally
    /// would rather not.
    func after(_ now: Int64) -> Int64? { self > now ? self : nil }
}
