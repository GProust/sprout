import ActivityKit
import Foundation
import SproutData

/// Keeps the Live Activities showing exactly the breastfeeds that are running —
/// one per feed, each with its own buttons (BDR-23, BDR-24) — or none, when the
/// switch in Settings is off or the parent turned Live Activities off for
/// Sprout in iOS Settings.
///
/// **Updates come from the app and only from the app.** There is no push: the
/// `aps-environment` entitlement is refused by `check_attack_surface.py`, and a
/// running feed is device-local anyway (BDR-17). Nothing ticks either: the
/// activity is handed a clock to count from, and the system counts. So this
/// runs when a feed changes — started, paused, resumed, switched, saved — and
/// when the app comes up with one still running.
///
/// Main-actor isolated because the session store is, and because every caller
/// already is: the store's writes, Settings, and the intents the buttons run.
@MainActor
enum NursingLiveActivities {

    /// The ordinary settings, once the app has opened them; until then there is
    /// no switch to read, so nothing is shown.
    private static var settings: (any DeviceLocalStore)?

    /// Hands over the settings once the app is up, and catches up with any feed
    /// that was already running when it came up.
    static func attach(settings: any DeviceLocalStore) {
        self.settings = settings
        sync(NursingSessionStore.shared.sessions)
    }

    /// Starts, updates or ends activities so that they match `sessions`.
    static func sync(_ sessions: [NursingSession]) {
        guard let settings else { return }
        let shown = NursingNotificationSettings.isEnabled(settings)
            && ActivityAuthorizationInfo().areActivitiesEnabled
            ? sessions
            : []
        let running = Activity<NursingActivityAttributes>.activities
        let wanted = Set(shown.map(key))

        // A feed saved or cancelled — wherever it was saved from — takes its
        // activity with it at once, rather than leaving a finished clock up.
        for activity in running where !wanted.contains(activity.attributes.babyUid) {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }

        let now = Clock.millis
        for (index, session) in shown.enumerated() {
            let state = contentState(session, number: index + 1, count: shown.count, now: now)
            let content = ActivityContent(state: state, staleDate: nil)
            if let activity = running.first(where: { $0.attributes.babyUid == key(session) }) {
                guard activity.content.state != state else { continue }
                Task { await activity.update(content) }
            } else {
                // Starting one is only allowed from the foreground, which is
                // where a feed is started. A refusal leaves the feed exactly as
                // it was: the activity is a view of it, never the record.
                _ = try? Activity.request(attributes: attributes(for: session), content: content, pushType: nil)
            }
        }
    }

    /// The activity's handle on a feed: its baby's uid, or nothing for a feed
    /// from before babies were recorded.
    private static func key(_ session: NursingSession) -> String { session.babyUid ?? "" }

    private static func attributes(for session: NursingSession) -> NursingActivityAttributes {
        NursingActivityAttributes(
            babyUid: key(session),
            labels: [
                NursingControl.PAUSE.rawValue: Str.t("feeding_pause"),
                NursingControl.SWITCH.rawValue: Str.t("nursing_switch_side"),
                NursingControl.RESUME_LEFT.rawValue: Str.t("feeding_resume_left"),
                NursingControl.RESUME_RIGHT.rawValue: Str.t("feeding_resume_right"),
                NursingControl.STOP.rawValue: Str.t("feeding_stop_save"),
            ]
        )
    }

    /// What one feed shows: never its baby's name, because a Live Activity is
    /// on the lock screen first — two feeds are *Feed 1* and *Feed 2* instead,
    /// as on Android's locked screen.
    private static func contentState(
        _ session: NursingSession,
        number: Int,
        count: Int,
        now: Int64
    ) -> NursingActivityAttributes.ContentState {
        // Time at the breast, never `now - segmentStart` (BDR-17).
        let nursed = session.nursedMs(at: now)
        let state = session.stateLabel
        return NursingActivityAttributes.ContentState(
            title: count > 1
                ? Str.t("nursing_notif_feed_number", number, state)
                : Str.t("home_live_nursing", state),
            detail: session.isPaused
                ? Str.t("nursing_notif_on_break", SproutFormat.duration(millis: nursed).text)
                : nil,
            isPaused: session.isPaused,
            clockStart: date(now - nursed),
            clockStoppedAt: session.isPaused ? date(now) : nil,
            controls: nursingControls(for: session).map(\.rawValue)
        )
    }

    private static func date(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    }
}

/// What a Live Activity's button does, in the app's process (ADR-0020): the act
/// the same button on the timer screen performs, on the same store.
///
/// The app may not be open when one is tapped — it may not even be running, and
/// then iOS starts it in the background for the intent alone, before any screen
/// exists. So the database is opened here if nothing has opened it yet, through
/// the same single ``AppEnvironment/live()`` the app's window uses.
enum NursingActivityControls {
    @MainActor
    static func perform(_ raw: String, babyUid: String?) async {
        guard let control = NursingControl(rawValue: raw) else { return }
        let store = NursingSessionStore.shared
        guard control == .STOP else {
            store.apply(control, for: babyUid, at: Clock.millis)
            return
        }
        // Opened before the feed is taken: a feed must never leave the store
        // with nowhere to be saved.
        guard let environment = try? AppEnvironment.live() else { return }
        // A second tap, or the timer saving it first, finds nothing to take.
        guard let taken = store.consume(babyUid) else { return }
        // To the baby the feed was started for, ending when its last stretch
        // ended — the same feed the timer's *Stop & save* saves.
        try? environment.repository.addTimedFeeding(taken.toFeeding(at: Clock.millis), startedFor: taken.babyUid)
        // The feeding reminder counts from this feed, and the app may stay
        // closed for hours: rebuilt now, as the medicine buttons do (ADR-0019).
        await ReminderScheduler.rebuild(repository: environment.repository, settings: environment.settingsStore)
    }
}
