import Foundation

// Two breastfeeds saved apart that were really one feed — *Stop & save* after
// the first side, the baby winded, *Start right* five minutes later — joined
// back into it (BDR-19), from `data/BreastfeedJoin.kt`.
//
// **A join is a pause that happened after the fact.** The minutes between the
// two feeds become a break: the gap between two stretches, which is all a
// break ever was (BDR-17). So the joined feed needs no field of its own, and
// every screen that already reads a break reads this one.
//
// The same arithmetic as Android's, tested with the same cases
// (`BreastfeedJoinTests`): a household with one phone of each has to offer the
// same join on the same two feeds.

/// How far apart two breastfeeds can be and still be offered as one.
///
/// Where the offer appears, not how long a break may be — a paused session can
/// still run all night. Past half an hour, two feeds side by side are far more
/// likely to be two feeds (cluster feeding is often an hour apart, start to
/// start) than a burp saved in the middle, and a *Join* on nearly every card
/// would be the opposite of quiet.
public let breastfeedJoinMaxGapMs: Int64 = 30 * 60 * 1000

/// The break joining `earlier` to `later` would leave, or nil when the two
/// cannot be joined.
///
/// Both have to be breastfeeds of the same baby, both timed stretch by stretch
/// — a feed with only a start, or only per-side totals from before stretches
/// were recorded, has no end to line up against — and `later` has to begin
/// once `earlier` has ended, at most ``breastfeedJoinMaxGapMs`` afterwards.
/// Zero is a join too: a switch of sides saved as two feeds.
public func breastfeedJoinGap(_ earlier: Feeding, _ later: Feeding) -> Int64? {
    guard earlier.type == .BREAST, later.type == .BREAST else { return nil }
    guard earlier.babyId == later.babyId, earlier.uid != later.uid else { return nil }
    guard let ended = earlier.nursingSegments.last?.endTime,
          let resumed = later.nursingSegments.first?.startTime
    else { return nil }
    guard later.startTime >= ended else { return nil }
    let gap = resumed - ended
    return (0...breastfeedJoinMaxGapMs).contains(gap) ? gap : nil
}

/// Which feeds on a history can be joined to the one before them: each later
/// feed's uid, mapped to the earlier feed it would join.
///
/// Only neighbours: if anything else was logged between two breastfeeds — a
/// bottle, solids — they were not one feed, and joining them would wrap the
/// joined feed around it.
public func breastfeedJoinOffers(_ feeds: [Feeding]) -> [String: Feeding] {
    // Stable, like Kotlin's `sortedBy`, so two feeds with the same start pair
    // up the same way on both platforms.
    let ordered = feeds.enumerated()
        .sorted { ($0.element.startTime, $0.offset) < ($1.element.startTime, $1.offset) }
        .map(\.element)
    var offers: [String: Feeding] = [:]
    for (earlier, later) in zip(ordered, ordered.dropFirst())
    where breastfeedJoinGap(earlier, later) != nil {
        offers[later.uid] = earlier
    }
    return offers
}

/// `earlier` and `later` as the one feed they were.
///
/// The earlier feed is the one that stays: it keeps its row, its uid and its
/// start, gains the later feed's stretches, and ends when the last of them did.
/// The per-side times are re-totalled from the stretches, so the time at the
/// breast is exactly what the two feeds said between them and the gap is not
/// in it. Notes from both are kept.
///
/// Callers check ``breastfeedJoinGap(_:_:)`` first; this does not.
public func joinedBreastfeed(_ earlier: Feeding, _ later: Feeding) -> Feeding {
    let segments = earlier.nursingSegments + later.nursingSegments
    let left = segments.filter { $0.side == .LEFT }.reduce(Int64(0)) { $0 + $1.durationMs }
    let right = segments.filter { $0.side == .RIGHT }.reduce(Int64(0)) { $0 + $1.durationMs }

    var notes: [String] = []
    for note in [earlier.notes, later.notes].compactMap({ $0 }) {
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty, !notes.contains(trimmed) { notes.append(trimmed) }
    }

    var joined = earlier
    if left > 0 && right > 0 {
        joined.side = .BOTH
    } else if right > 0 {
        joined.side = .RIGHT
    } else if left > 0 {
        joined.side = .LEFT
    }
    joined.endTime = segments.last?.endTime ?? earlier.endTime
    joined.leftDurationMs = left > 0 ? left : nil
    joined.rightDurationMs = right > 0 ? right : nil
    joined.segments = NursingSegmentCoding.encode(segments)
    joined.notes = notes.isEmpty ? nil : notes.joined(separator: "\n")
    return joined
}
