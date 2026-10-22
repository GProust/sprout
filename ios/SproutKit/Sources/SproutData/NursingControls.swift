import Foundation

/// What a running feed can be asked to do — from the timer screen, and from the
/// Live Activity that shows it outside the app (BDR-24), from
/// `ui/feeding/NursingControls.kt`.
///
/// One list for every place that offers them, so a tap outside the app is the
/// same act as the same tap on the timer: the same function changes the same
/// session, and *Stop & save* saves the same feed. The same cases are tested on
/// Android.
public enum NursingControl: String, CaseIterable, Codable, Sendable {
    case PAUSE, SWITCH, RESUME_LEFT, RESUME_RIGHT, STOP
}

/// The controls a feed offers outside the app, in the order they are shown:
/// three at most, which is what a notification has room for.
///
/// Coming back from a break names the breast (BDR-17), so a feed on a break
/// offers *Resume left* and *Resume right* rather than a single *Resume*.
public func nursingControls(for session: NursingSession) -> [NursingControl] {
    session.isPaused ? [.RESUME_LEFT, .RESUME_RIGHT, .STOP] : [.PAUSE, .SWITCH, .STOP]
}

extension NursingSession {
    /// This feed after `control`, tapped at `now`.
    ///
    /// A control that does not fit the feed's state changes nothing: a Live
    /// Activity can be a tap behind the app, and a *Switch* left on screen while
    /// the feed went on a break must not quietly resume it. Pausing twice is
    /// already nothing (``paused(at:)``). `.STOP` leaves the feed as it is —
    /// ending it is the store's to do, so that only one tap is ever handed it.
    public func after(_ control: NursingControl, at now: Int64) -> NursingSession {
        switch control {
        case .PAUSE: return paused(at: now)
        case .SWITCH: return isPaused ? self : switched(at: now)
        case .RESUME_LEFT: return isPaused ? resumed(on: .LEFT, at: now) : self
        case .RESUME_RIGHT: return isPaused ? resumed(on: .RIGHT, at: now) : self
        case .STOP: return self
        }
    }
}

/// Whether a running breastfeed is shown outside the app — the notification on
/// Android, the Live Activity here (BDR-24) — from
/// `NursingNotificationSettings.kt`. Off until a parent turns it on, like every
/// other thing Sprout shows outside the app; the same key as Android's.
public enum NursingNotificationSettings {

    static let enabledKey = "nursing_notification_enabled"

    public static func isEnabled(_ store: any DeviceLocalStore) -> Bool {
        store.bool(forKey: enabledKey, default: false)
    }

    public static func setEnabled(_ enabled: Bool, in store: any DeviceLocalStore) {
        store.setBool(enabled, forKey: enabledKey)
    }
}
