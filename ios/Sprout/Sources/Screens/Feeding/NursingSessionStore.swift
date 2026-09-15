import Foundation
import Observation
import SproutData

/// One uninterrupted breastfeeding session in progress.
public struct NursingSession: Equatable, Sendable {
    public var sessionStart: Int64
    public var currentSide: BreastSide
    public var segmentStart: Int64
    public var segments: [NursingSegment] = []

    /// Every stretch including the one still running at `now`.
    func allSegments(endingAt now: Int64) -> [NursingSegment] {
        segments + [NursingSegment(side: currentSide, startTime: segmentStart, endTime: now)]
    }
}

/// The one live breastfeeding session, from `ui/feeding/NursingSessionStore.kt`.
///
/// Held in `UserDefaults` so it survives the app being killed and can be read by
/// the widget, and published so that everything showing it is looking at the
/// *same* session.
///
/// **The sharing is not a convenience.** The timer is reachable from several
/// places — the feeding screen's bar, the dashboard's live card, a widget tap
/// landing on Feeding while the timer is already open — and while each of those
/// kept a private copy, stopping the feed in one left the others still holding
/// it, and every one of them would happily save it again: *one breastfeed,
/// logged three times*. That was a real bug on Android, and this is the shape
/// of its fix.
///
/// For the same reason, ending a session goes through ``consume()`` rather than
/// a read-then-clear: whoever is handed the session is the one that saves it,
/// and there is nothing left for a second screen — or a second tap — to save.
///
/// Main-actor isolation is what makes ``startIfIdle(_:)`` and ``consume()``
/// atomic here; on Android that is a `@Synchronized` block.
@Observable
@MainActor
final class NursingSessionStore {

    static let shared = NursingSessionStore()

    private(set) var session: NursingSession?

    /// `UserDefaults` rather than the database: this is device-local state, not
    /// a row anybody syncs. A half-finished feed on one phone is not something
    /// the other should be told about — only the completed feeding is.
    private let defaults: UserDefaults
    private let key = "nursing_session"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        session = Self.read(from: defaults, key: key)
    }

    /// Starts `candidate` unless one is already running, so two entry points
    /// racing to open the timer cannot end up timing two feeds at once. Returns
    /// whichever session is running afterwards.
    @discardableResult
    func startIfIdle(_ candidate: NursingSession) -> NursingSession {
        if let existing = session { return existing }
        write(candidate)
        return candidate
    }

    /// Bank the current breast as a completed stretch and switch to the other.
    func switchBreast(at now: Int64) {
        guard let current = session else { return }
        var next = current
        next.segments.append(
            NursingSegment(side: current.currentSide, startTime: current.segmentStart, endTime: now)
        )
        next.currentSide = current.currentSide == .LEFT ? .RIGHT : .LEFT
        next.segmentStart = now
        write(next)
    }

    /// Takes the session and clears it, in one step.
    ///
    /// The atomicity is the point: whoever gets a value back is the only one
    /// who will, so a second screen still showing the timer has nothing to save.
    func consume() -> NursingSession? {
        defer { write(nil) }
        return session
    }

    func clear() { write(nil) }

    // MARK: - Storage

    private func write(_ value: NursingSession?) {
        session = value
        guard let value else {
            defaults.removeObject(forKey: key)
            return
        }
        defaults.set(
            [
                "sessionStart": value.sessionStart,
                "currentSide": value.currentSide.rawValue,
                "segmentStart": value.segmentStart,
                // The same encoding Room's converter uses, so the widget and a
                // future App Group read the bytes the app wrote.
                "segments": NursingSegmentCoding.encode(value.segments),
            ],
            forKey: key
        )
    }

    private static func read(from defaults: UserDefaults, key: String) -> NursingSession? {
        guard
            let stored = defaults.dictionary(forKey: key),
            let start = stored["sessionStart"] as? Int64 ?? (stored["sessionStart"] as? NSNumber)?.int64Value,
            start > 0,
            let sideName = stored["currentSide"] as? String,
            let side = BreastSide(rawValue: sideName)
        else { return nil }

        let segmentStart = (stored["segmentStart"] as? NSNumber)?.int64Value ?? start
        return NursingSession(
            sessionStart: start,
            currentSide: side,
            segmentStart: segmentStart,
            segments: NursingSegmentCoding.decode(stored["segments"] as? String)
        )
    }
}
