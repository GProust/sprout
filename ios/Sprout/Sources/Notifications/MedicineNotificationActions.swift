import SproutData
import UserNotifications

/// The identifiers, at file scope rather than on the class.
///
/// They are read from two places that are not on the main actor together: the
/// scheduler writes them into a notification, and the delegate callback reads
/// one back out before it hops to the main actor. Constants nobody owns are the
/// simplest thing that is true for both.
enum MedicineNotification {
    /// The category the medicine reminders carry, and the two actions on it.
    static let category = "sprout.medicine"
    static let giveAction = "sprout.medicine.give"
    static let dismissAction = "sprout.medicine.dismiss"

    /// The medicine's row id, carried in the notification's `userInfo`.
    ///
    /// The id and not the uid, because this only ever addresses a row on *this*
    /// phone — the notification was scheduled here and is answered here.
    static let medicineIdKey = "medicineId"
}

/// The two buttons on an as-needed medicine's reminder: *Give a dose* and
/// *Dismiss* (BDR-16). The mirror of Android's
/// `MedicineReminderReceiver.ACTION_GIVE` / `ACTION_DISMISS`.
///
/// This is the one place where something of Sprout's runs *after* a
/// notification was scheduled, and it does not contradict ADR-0019: the
/// decision about what to *say* is still taken when the reminder is planned.
/// What runs here is the parent's answer to it, which iOS delivers the same way
/// on both sides of that line.
///
/// A dose logged from the notification is logged at the moment it was given. The
/// alternative is a parent giving it at 3 a.m. and recording it at nine, which
/// puts the next wait six hours late — the arithmetic the whole feature is.
@MainActor
final class MedicineNotificationActions: NSObject, UNUserNotificationCenterDelegate {

    static let shared = MedicineNotificationActions()

    /// The app, once it has opened its database.
    private var environment: AppEnvironment?

    /// Doses tapped before the database was open.
    ///
    /// Tapping *Give a dose* on the lock screen can launch the app from cold,
    /// and the response arrives before the first frame. Dropping it would lose
    /// exactly the dose the button exists to capture, so it waits here for
    /// ``attach(_:)`` — a handful of milliseconds later, and at the time it was
    /// actually given rather than at the time it was finally written.
    private var pending: [(medicineId: Int64, at: Int64)] = []

    /// Declares the category. Must run before the app finishes launching, or a
    /// tap that launched it is delivered to nobody.
    func register() {
        let centre = UNUserNotificationCenter.current()
        centre.delegate = self
        centre.setNotificationCategories([
            UNNotificationCategory(
                identifier: MedicineNotification.category,
                actions: [
                    UNNotificationAction(
                        identifier: MedicineNotification.giveAction,
                        title: Str.t("medicine_give"),
                        // No `.authenticationRequired`: the phone is on a
                        // bedside table at 3 a.m. and the dose has already been
                        // given. Asking for Face ID first is how it goes
                        // unlogged. Nothing about the baby is shown or changed
                        // beyond the row this adds.
                        options: []
                    ),
                    UNNotificationAction(
                        identifier: MedicineNotification.dismissAction,
                        title: Str.t("medicine_dismiss"),
                        options: []
                    ),
                ],
                intentIdentifiers: [],
                options: []
            )
        ])
    }

    /// Hands over the database once it is open, and drains anything that was
    /// tapped before it was.
    func attach(_ environment: AppEnvironment) {
        self.environment = environment
        let queued = pending
        pending = []
        for item in queued {
            apply(medicineId: item.medicineId, at: item.at)
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let action = response.actionIdentifier
        // Read out of `userInfo` here, where the notification still is: only the
        // two values cross to the main actor, and both of them are `Sendable`.
        let id = (response.notification.request.content.userInfo[MedicineNotification.medicineIdKey] as? NSNumber)?
            .int64Value
        // The moment the button was tapped, not the moment the work reaches the
        // database.
        let at = Clock.millis

        guard action == MedicineNotification.giveAction, let id else {
            // *Dismiss*, a tap on the body, or a swipe away. iOS has already
            // taken the notification down and the wait really is over, so there
            // is nothing to snooze and nothing to record — the parent has seen
            // it, which is all the button claims.
            return
        }
        await give(medicineId: id, at: at)
    }

    private func give(medicineId: Int64, at time: Int64) {
        guard environment != nil else {
            pending.append((medicineId: medicineId, at: time))
            return
        }
        apply(medicineId: medicineId, at: time)
    }

    private func apply(medicineId: Int64, at time: Int64) {
        guard let environment,
              let medicine = try? environment.repository.medicine(id: medicineId),
              medicine.deletedAt == nil
        else { return }

        try? environment.repository.giveMedicineDose(medicine, at: time)

        // Rebuilt here rather than left to the next foregrounding: the whole
        // point of the button is that the app is *not* opened, so the two
        // moments `SproutApp` rebuilds at may be hours away (ADR-0019). Without
        // this the next reminder would still be armed off the previous dose.
        let repository = environment.repository
        let settings = environment.settingsStore
        Task { await ReminderScheduler.rebuild(repository: repository, settings: settings) }
    }
}
