import Foundation

/// A breastfeeding session being timed live but not yet saved, from
/// `ui/feeding/NursingSession.kt`.
///
/// `segments` holds the *completed* back-and-forth stretches; the breast
/// currently nursing runs from `segmentStart` until the next switch, break or
/// stop, ticking off the clock.
///
/// A session on a break — winding for a burp between the sides, a nappy in the
/// middle — has already banked the stretch it was on and is nursing nothing:
/// `pausedAt` is when the break started and `segmentStart` means nothing until
/// it resumes. That is why every clock is read through ``nursedMs(at:)`` and
/// never from `now - segmentStart`: a reader that forgets the break counts the
/// burping as time at the breast, which is the one number the timer exists to
/// get right (BDR-17).
///
/// It lives in `SproutData` rather than beside its screen so that the
/// arithmetic can be tested the way Android tests it. The store that holds the
/// running session is the app's, and device-local.
public struct NursingSession: Equatable, Sendable {
    public var sessionStart: Int64
    public var currentSide: BreastSide
    public var segmentStart: Int64
    public var segments: [NursingSegment]
    /// When the current break began; nil while a breast is actually nursing.
    public var pausedAt: Int64?

    public init(
        sessionStart: Int64,
        currentSide: BreastSide,
        segmentStart: Int64,
        segments: [NursingSegment] = [],
        pausedAt: Int64? = nil
    ) {
        self.sessionStart = sessionStart
        self.currentSide = currentSide
        self.segmentStart = segmentStart
        self.segments = segments
        self.pausedAt = pausedAt
    }

    public var isPaused: Bool { pausedAt != nil }

    /// Every stretch so far, the one in progress included (it ends at `now`).
    /// On a break there is none in progress — it was banked when the break
    /// began — so this is exactly what has been nursed.
    public func allSegments(endingAt now: Int64) -> [NursingSegment] {
        isPaused
            ? segments
            : segments + [NursingSegment(side: currentSide, startTime: segmentStart, endTime: now)]
    }

    /// Time actually spent at the breast so far, breaks excluded.
    public func nursedMs(at now: Int64) -> Int64 {
        allSegments(endingAt: now).reduce(0) { $0 + $1.durationMs }
    }

    /// Time spent on `side` so far, breaks excluded.
    public func nursedMs(on side: BreastSide, at now: Int64) -> Int64 {
        allSegments(endingAt: now).filter { $0.side == side }.reduce(0) { $0 + $1.durationMs }
    }

    /// How long the current break has been running; zero while nursing.
    public func pausedMs(at now: Int64) -> Int64 {
        guard let pausedAt else { return 0 }
        return max(0, now - pausedAt)
    }

    /// Bank the breast being nursed and start a break. Pausing an already
    /// paused session changes nothing — a second tap must not restart the
    /// break, or the minutes spent burping would quietly reset.
    public func paused(at now: Int64) -> NursingSession {
        guard !isPaused else { return self }
        var next = self
        next.segments = allSegments(endingAt: now)
        next.pausedAt = now
        return next
    }

    /// Come back from a break, on `side` — the same breast or the other one.
    public func resumed(on side: BreastSide, at now: Int64) -> NursingSession {
        var next = self
        next.currentSide = side
        next.segmentStart = now
        next.pausedAt = nil
        return next
    }

    /// Bank the current breast and carry straight on with the other one: a
    /// break that begins and ends at the same instant, which is what switching
    /// sides has always been.
    public func switched(at now: Int64) -> NursingSession {
        paused(at: now).resumed(on: currentSide.other, at: now)
    }
}

extension BreastSide {
    /// The other breast. `.BOTH` is a property of a saved feed, never of a live one.
    public var other: BreastSide { self == .LEFT ? .RIGHT : .LEFT }
}

/// The breaks inside a finished breastfeed: the time between one stretch ending
/// and the next beginning. Nothing records a break of its own — it is the gap
/// the segments leave, which is why a feed logged before breaks existed reads
/// back as a feed with none.
public func breastfeedPausedMillis(_ segments: [NursingSegment]) -> Int64 {
    zip(segments, segments.dropFirst())
        .reduce(Int64(0)) { $0 + max(0, $1.1.startTime - $1.0.endTime) }
}
