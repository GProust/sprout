import Foundation

/// Whether a course of treatment is behind us, from `ui/treatments/`.
///
/// Three rules, and each of them is a boundary somebody meets:
///
/// - **The end date is inclusive**, and compared by *calendar day*. A treatment
///   whose last dose is today stays in the active section until midnight — a
///   parent with one dose left to give should not have to look for it under
///   "finished".
/// - **No end date never ends.** An ongoing course has nothing to be past.
/// - **A course that has not started yet is ahead, not behind.** Nothing about
///   the start date can make it past.
public func hasEnded(_ treatment: Treatment, now: Int64) -> Bool {
    guard let end = treatment.endDate else { return false }
    // "Before today's midnight" is the whole comparison: an end stored at one
    // minute past midnight today is not before it, and one at 23:59 yesterday
    // is. Comparing the two instants directly would put the first in the past
    // by half a day.
    return end < SproutFormat.startOfDay(now)
}

/// The list split the way the screen draws it: still running (or not started)
/// on top in the query's order, finished underneath with the most recent first.
public func treatmentSections(
    _ treatments: [Treatment],
    now: Int64
) -> (current: [Treatment], past: [Treatment]) {
    let past = treatments
        .filter { hasEnded($0, now: now) }
        .sorted { ($0.endDate ?? 0) > ($1.endDate ?? 0) }
    let current = treatments.filter { !hasEnded($0, now: now) }
    return (current, past)
}
