import Foundation
import Observation
import SproutData

/// The live breastfeeding sessions, at most one per baby (BDR-23), from
/// `ui/feeding/NursingSessionStore.kt`.
///
/// Held in `UserDefaults` so they survive the app being killed, and published
/// so that everything showing one is looking at the *same* session.
///
/// **The sharing is not a convenience.** A timer is reachable from several
/// places — the feeding screen's bar, the dashboard's live card, a widget tap
/// landing on Feeding while the timer is already open — and while each of those
/// kept a private copy, stopping the feed in one left the others still holding
/// it, and every one of them would happily save it again: *one breastfeed,
/// logged three times*. That was a real bug on Android, and this is the shape
/// of its fix.
///
/// For the same reason, ending a session goes through ``consume(_:)`` rather
/// than a read-then-clear: whoever is handed the session is the one that saves
/// it, and there is nothing left for a second screen — or a second tap — to
/// save. What each act does to the list is decided in SproutData's
/// `NursingSessions.swift`, tested with Android's cases; this only keeps it.
///
/// Main-actor isolation is what makes ``startIfIdle(_:)`` and ``consume(_:)``
/// atomic here; on Android that is a `@Synchronized` block.
@Observable
@MainActor
final class NursingSessionStore {

    static let shared = NursingSessionStore()

    /// Every running feed, oldest first.
    private(set) var sessions: [NursingSession] = []

    /// `UserDefaults` rather than the database: this is device-local state, not
    /// a row anybody syncs. A half-finished feed on one phone is not something
    /// the other should be told about — only the completed feeding is.
    private let defaults: UserDefaults
    private let key = "nursing_sessions"
    /// Where a version before BDR-23 kept its one session. Read once, and
    /// removed on the next write.
    private let legacyKey = "nursing_session"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        sessions = Self.read(from: defaults, key: key, legacyKey: legacyKey)
    }

    /// The feed running for `babyUid`, or nil.
    func session(for babyUid: String?) -> NursingSession? {
        sessions.runningFor(babyUid)
    }

    /// Starts `candidate` unless its baby already has one running, so two entry
    /// points racing to open the timer cannot end up timing one baby twice.
    /// Another baby's feed is no obstacle. Returns whichever session is running
    /// for that baby afterwards.
    @discardableResult
    func startIfIdle(_ candidate: NursingSession) -> NursingSession {
        let start = startingIfIdle(sessions, candidate)
        if start.sessions != sessions { write(start.sessions) }
        return start.running
    }

    /// Bank `babyUid`'s current breast as a completed stretch and switch to the other.
    func switchBreast(for babyUid: String?, at now: Int64) {
        apply(.SWITCH, for: babyUid, at: now)
    }

    /// Bank the breast `babyUid` is nursing at and start a break — the burp
    /// between the sides, the nappy halfway through. The session stays open and
    /// keeps its start time; only the clock at the breast stops (BDR-17).
    /// Another baby's feed carries on.
    func pause(for babyUid: String?, at now: Int64) {
        apply(.PAUSE, for: babyUid, at: now)
    }

    /// Come back from a break, on whichever breast `babyUid`'s feed carries on with.
    func resume(for babyUid: String?, on side: BreastSide, at now: Int64) {
        apply(side == .RIGHT ? .RESUME_RIGHT : .RESUME_LEFT, for: babyUid, at: now)
    }

    /// `control` on `babyUid`'s running feed and nothing else — from the timer
    /// screen or from a Live Activity's button, which is the point: both are the
    /// same act (BDR-24). One that no longer fits the feed changes nothing.
    /// `.STOP` is ``consume(_:)``, not this.
    func apply(_ control: NursingControl, for babyUid: String?, at now: Int64) {
        change(babyUid) { $0.after(control, at: now) }
    }

    /// Takes `babyUid`'s session and clears it, in one step. Every other baby's
    /// feed carries on.
    ///
    /// The atomicity is the point: whoever gets a value back is the only one
    /// who will, so a second screen still showing the timer has nothing to save.
    func consume(_ babyUid: String?) -> NursingSession? {
        let take = taking(sessions, babyUid)
        if take.taken != nil { write(take.sessions) }
        return take.taken
    }

    /// Gives a feed left running by a version that did not record its baby to
    /// `activeBabyUid`, the baby it would have been saved to (BDR-23). Called
    /// once the app is up; does nothing when there is no such feed.
    func adoptUnnamed(activeBabyUid: String?) {
        let next = adoptingUnnamed(sessions, activeBabyUid: activeBabyUid)
        if next != sessions { write(next) }
    }

    private func change(_ babyUid: String?, _ transform: (NursingSession) -> NursingSession) {
        let next = updating(sessions, babyUid, transform)
        if next != sessions { write(next) }
    }

    // MARK: - Storage

    private func write(_ value: [NursingSession]) {
        sessions = value
        defaults.removeObject(forKey: legacyKey)
        if value.isEmpty {
            defaults.removeObject(forKey: key)
        } else {
            defaults.set(value.map(Self.encode), forKey: key)
        }
        // Whatever changed it — the timer, the dashboard, a Live Activity's own
        // button — the Live Activities follow (BDR-24).
        NursingLiveActivities.sync(value)
    }

    private static func encode(_ value: NursingSession) -> [String: Any] {
        var stored: [String: Any] = [
            "sessionStart": value.sessionStart,
            "currentSide": value.currentSide.rawValue,
            "segmentStart": value.segmentStart,
            // The same encoding Room's converter uses, so the bytes the app
            // wrote are the bytes any reader of them expects.
            "segments": NursingSegmentCoding.encode(value.segments),
            // Zero is "not on a break", the way an absent key reads: a session
            // stored before breaks existed was nursing, and comes back as one
            // that is.
            "pausedAt": value.pausedAt ?? 0,
        ]
        // Left out when there is none, which is how a session from before the
        // baby was recorded reads.
        if let babyUid = value.babyUid { stored["babyUid"] = babyUid }
        return stored
    }

    private static func decode(_ stored: [String: Any]) -> NursingSession? {
        guard
            let start = (stored["sessionStart"] as? NSNumber)?.int64Value,
            start > 0,
            let sideName = stored["currentSide"] as? String,
            let side = BreastSide(rawValue: sideName)
        else { return nil }

        let segmentStart = (stored["segmentStart"] as? NSNumber)?.int64Value ?? start
        let pausedAt = (stored["pausedAt"] as? NSNumber)?.int64Value ?? 0
        return NursingSession(
            sessionStart: start,
            currentSide: side,
            segmentStart: segmentStart,
            segments: NursingSegmentCoding.decode(stored["segments"] as? String),
            pausedAt: pausedAt > 0 ? pausedAt : nil,
            // Absent for a session stored before the baby was recorded: it is
            // given to the selected baby when the app comes up (adoptUnnamed).
            babyUid: stored["babyUid"] as? String
        )
    }

    private static func read(from defaults: UserDefaults, key: String, legacyKey: String) -> [NursingSession] {
        if let stored = defaults.array(forKey: key) as? [[String: Any]] {
            return stored.compactMap(decode)
        }
        // The one session a version before BDR-23 kept, if it left one running.
        if let single = defaults.dictionary(forKey: legacyKey), let session = decode(single) {
            return [session]
        }
        return []
    }
}
