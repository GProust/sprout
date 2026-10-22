import Foundation

// Every breastfeed being timed on this phone, **at most one per baby**
// (BDR-23), from `ui/feeding/NursingSessions.kt`.
//
// Twins feed together — one at each breast is the everyday case — so the phone
// can time more than one feed at once. A baby has one mouth, though, so it can
// never time two for the same child: a second start for a baby whose feed is
// running hands back the running one, which is the race guard the single
// session always had, kept per baby.
//
// These are the rules as pure functions of the list. The app's
// `NursingSessionStore` holds the list and applies them; the same cases are
// tested on Android, because a household with one phone of each has to agree
// on what a tap does.

extension Array where Element == NursingSession {
    /// The feed running for `babyUid`, or nil when that baby is not feeding.
    public func runningFor(_ babyUid: String?) -> NursingSession? {
        first { $0.babyUid == babyUid }
    }
}

/// What a start leaves behind: every running feed, and the one for that baby.
public struct NursingStart: Equatable, Sendable {
    public let sessions: [NursingSession]
    public let running: NursingSession
}

/// Starts `candidate` unless its baby already has a feed running, in which case
/// that one is handed back untouched. Another baby's feed is no obstacle.
public func startingIfIdle(_ sessions: [NursingSession], _ candidate: NursingSession) -> NursingStart {
    if let running = sessions.runningFor(candidate.babyUid) {
        return NursingStart(sessions: sessions, running: running)
    }
    return NursingStart(sessions: inOrder(sessions + [candidate]), running: candidate)
}

/// `session` in place of whatever its baby had running.
public func replacing(_ sessions: [NursingSession], _ session: NursingSession) -> [NursingSession] {
    inOrder(sessions.filter { $0.babyUid != session.babyUid } + [session])
}

/// The running feed for `babyUid`, changed by `change`; nothing at all when that
/// baby has none. Pause, resume and switch act on one feed only — pausing one
/// twin's feed is not a reason to stop the other's clock.
public func updating(
    _ sessions: [NursingSession],
    _ babyUid: String?,
    _ change: (NursingSession) -> NursingSession
) -> [NursingSession] {
    guard let current = sessions.runningFor(babyUid) else { return sessions }
    return replacing(sessions, change(current))
}

/// What a stop leaves behind: the other feeds, and the one taken (or nil).
public struct NursingTake: Equatable, Sendable {
    public let sessions: [NursingSession]
    public let taken: NursingSession?
}

/// Takes `babyUid`'s feed away, leaving every other baby's running. Nil when
/// there was nothing to take — what a second tap on *Save*, or a second screen
/// still showing the finished timer, is handed.
public func taking(_ sessions: [NursingSession], _ babyUid: String?) -> NursingTake {
    guard let taken = sessions.runningFor(babyUid) else {
        return NursingTake(sessions: sessions, taken: nil)
    }
    return NursingTake(sessions: sessions.filter { $0.babyUid != babyUid }, taken: taken)
}

/// Which breast to offer a baby whose feed is about to start.
///
/// With another baby already at one breast, the free one: that is what tandem
/// feeding is. A default and never a rule — nothing stops two feeds on one side,
/// because the parent model assumes no single breastfeeding parent (BDR-1). With
/// nobody else nursing, or with both breasts taken, it is `fromHistory`: the
/// alternation the baby's own last feed suggests, or nil to offer both evenly.
public func sideForNextFeed(
    _ sessions: [NursingSession],
    babyUid: String?,
    fromHistory: BreastSide?
) -> BreastSide? {
    let taken = Set(
        sessions
            .filter { $0.babyUid != babyUid && !$0.isPaused }
            .map(\.currentSide)
    )
    guard taken.count == 1, let side = taken.first else { return fromHistory }
    return side.other
}

/// A feed left running by a version that did not record its baby, given one.
///
/// It belonged to the baby selected at the time, since that is where it would
/// have been saved; so it is read once as the baby selected now, at the moment
/// of the update, and from then on it is that baby's like any other. With no
/// baby to give it, or one that somehow has a feed of its own already, it is
/// left as it is — and saved, in the end, to whoever is selected.
public func adoptingUnnamed(_ sessions: [NursingSession], activeBabyUid: String?) -> [NursingSession] {
    guard let activeBabyUid,
          let unnamed = sessions.runningFor(nil),
          sessions.runningFor(activeBabyUid) == nil
    else { return sessions }
    var adopted = unnamed
    adopted.babyUid = activeBabyUid
    return replacing(sessions.filter { $0.babyUid != nil }, adopted)
}

/// Oldest first: the order the dashboard lists them in, and a stable one.
private func inOrder(_ sessions: [NursingSession]) -> [NursingSession] {
    sessions.sorted {
        ($0.sessionStart, $0.babyUid ?? "") < ($1.sessionStart, $1.babyUid ?? "")
    }
}

extension NursingSession {
    /// The feed this session saves as, stopped at `now`: every stretch so far,
    /// the side or sides they were on, and `notes` if any. Its baby is not set
    /// here — that is resolved from `babyUid` by the repository, which is the
    /// only place that stamps a row.
    ///
    /// One function for every way a feed can be stopped, so a feed saved from
    /// the timer screen and one saved from anywhere else are the same feed.
    public func toFeeding(at now: Int64, notes: String = "") -> Feeding {
        let all = allSegments(endingAt: now)
        let left = all.filter { $0.side == .LEFT }.reduce(Int64(0)) { $0 + $1.durationMs }
        let right = all.filter { $0.side == .RIGHT }.reduce(Int64(0)) { $0 + $1.durationMs }
        let side: BreastSide = {
            if left > 0 && right > 0 { return .BOTH }
            if right > 0 { return .RIGHT }
            return .LEFT
        }()
        let trimmed = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        return Feeding(
            type: .BREAST,
            side: side,
            startTime: sessionStart,
            // The feed ended when the last stretch did, not when Save was
            // tapped: a session saved from a break would otherwise carry the
            // whole break as feeding time it never was.
            endTime: all.last?.endTime ?? now,
            leftDurationMs: left > 0 ? left : nil,
            rightDurationMs: right > 0 ? right : nil,
            segments: all,
            notes: trimmed.isEmpty ? nil : trimmed
        )
    }
}
