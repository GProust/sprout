import SproutData
import UserNotifications

/// Handing the moments `Reminders.swift` computes to iOS (ADR-0019).
///
/// **The decision is taken here, not when the notification fires**, because on
/// iOS nothing of ours runs when it fires. Android arms an alarm that wakes a
/// receiver, which reads the database and decides what to say; there is no
/// equivalent, so the sentence is written in advance and the whole schedule is
/// rebuilt whenever the answer could have changed.
///
/// Nothing here asks for anything until a parent turns a reminder on.
@MainActor
enum ReminderScheduler {

    /// Ours, so a rebuild can clear them without touching anything else. Nothing
    /// else in this app schedules a notification, but the prefix costs one
    /// string and makes that a fact rather than an assumption.
    private static let prefix = "sprout."

    /// How many occurrences of a longer-cycle treatment to schedule ahead.
    ///
    /// iOS keeps at most 64 pending notifications per app and silently drops the
    /// rest. Four ahead per time of day leaves room for several babies and
    /// several treatments inside that, and the app tops them up every time it
    /// comes to the foreground.
    private static let occurrencesAhead = 4

    // MARK: - Permission

    /// Asks, once, at the moment a reminder is turned on.
    ///
    /// Returns whether Sprout may notify. A refusal is not an error and not
    /// something to argue with — the switch simply goes back off, so the screen
    /// never shows a reminder as on while iOS is dropping it.
    static func requestAuthorization() async -> Bool {
        let centre = UNUserNotificationCenter.current()
        let settings = await centre.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .denied:
            // Asking again does nothing: iOS shows the prompt once, and after a
            // refusal only Settings can change it.
            return false
        default:
            return (try? await centre.requestAuthorization(options: [.alert, .sound])) ?? false
        }
    }

    static func isAuthorized() async -> Bool {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        default: return false
        }
    }

    // MARK: - Rebuilding

    /// Cancels every reminder of ours and re-adds the ones still wanted.
    ///
    /// From scratch, deliberately. It is a few dozen entries, and the
    /// alternative — working out which ones moved — is the kind of bookkeeping
    /// that goes wrong quietly and leaves a parent with a reminder about a feed
    /// they logged an hour ago.
    static func rebuild(repository: SproutRepository, settings store: any DeviceLocalStore) async {
        let centre = UNUserNotificationCenter.current()

        // Read everything first, so a failure part-way leaves the old schedule
        // standing rather than an empty one.
        guard let plan = try? plan(repository: repository, settings: store) else { return }

        let pending = await centre.pendingNotificationRequests()
        centre.removePendingNotificationRequests(
            withIdentifiers: pending.map(\.identifier).filter { $0.hasPrefix(prefix) }
        )

        guard !plan.isEmpty, await isAuthorized() else { return }
        for request in plan {
            try? await centre.add(request)
        }
    }

    /// Everything Sprout wants to say, and when.
    ///
    /// Separate from the scheduling so that what gets planned is decided in one
    /// pass over the database rather than interleaved with calls to iOS.
    private static func plan(
        repository: SproutRepository,
        settings store: any DeviceLocalStore
    ) throws -> [UNNotificationRequest] {
        let now = Clock.millis
        let babies = try repository.activeBabies()
        // With one baby the notifications say "Time for a feed?"; with two they
        // have to say which. Android makes the same distinction with the same
        // two strings.
        let namesNeeded = babies.count > 1

        var requests: [UNNotificationRequest] = []
        for baby in babies {
            guard let babyId = baby.id else { continue }
            requests += try feedingRequests(
                baby: baby,
                babyId: babyId,
                repository: repository,
                store: store,
                namesNeeded: namesNeeded,
                now: now
            )
            requests += growthSpurtRequests(
                baby: baby,
                babyId: babyId,
                store: store,
                namesNeeded: namesNeeded,
                now: now
            )
            requests += try treatmentRequests(
                babyId: babyId,
                babyName: baby.name,
                repository: repository,
                namesNeeded: namesNeeded,
                now: now
            )
        }
        return requests
    }

    // MARK: - The three kinds

    private static func feedingRequests(
        baby: Baby,
        babyId: Int64,
        repository: SproutRepository,
        store: any DeviceLocalStore,
        namesNeeded: Bool,
        now: Int64
    ) throws -> [UNNotificationRequest] {
        let effective = effectiveFeedingReminder(baby: baby, settings: store)
        guard effective.enabled else { return [] }
        // No feeds yet is nothing to remind about, not a reminder at zero.
        guard let last = try repository.lastFeedTime(babyId: babyId) else { return [] }

        let trigger = feedingReminderTrigger(
            lastFeedTime: last,
            intervalMinutes: effective.intervalMinutes
        )
        // Already overdue: dropped rather than delivered late. The only moment we
        // could deliver it is the app being opened, and a notification that
        // arrives *because* the parent opened Sprout tells them nothing (ADR-0019).
        guard trigger > now else { return [] }

        let content = UNMutableNotificationContent()
        content.title = namesNeeded
            ? Str.t("feeding_notif_title_baby", baby.name)
            : Str.t("feeding_notif_title")
        content.body = Str.t(
            "feeding_notif_text",
            SproutFormat.duration(millis: Int64(effective.intervalMinutes) * 60_000).text
        )
        content.sound = .default

        return [request(id: "\(prefix)feeding.\(babyId)", content: content, at: trigger, now: now)]
    }

    private static func growthSpurtRequests(
        baby: Baby,
        babyId: Int64,
        store: any DeviceLocalStore,
        namesNeeded: Bool,
        now: Int64
    ) -> [UNNotificationRequest] {
        guard GrowthSpurtSettings.isEnabled(store) else { return [] }
        guard let trigger = nextGrowthSpurtTrigger(birthDate: baby.birthDate, now: now)
        else { return [] }

        let content = UNMutableNotificationContent()
        content.title = namesNeeded
            ? Str.t("growth_spurt_notif_title_baby", baby.name)
            : Str.t("growth_spurt_notif_title")
        content.body = Str.t("growth_spurt_notif_text", spurtWindowLabel(birthDate: baby.birthDate, at: trigger))
        // No sound: this is information, and it is not worth a household waking
        // up for. Android gives it the same gentle treatment by firing it at 09:00.
        return [request(id: "\(prefix)spurt.\(babyId)", content: content, at: trigger, now: now)]
    }

    private static func treatmentRequests(
        babyId: Int64,
        babyName: String,
        repository: SproutRepository,
        namesNeeded: Bool,
        now: Int64
    ) throws -> [UNNotificationRequest] {
        var requests: [UNNotificationRequest] = []

        for treatment in try repository.treatmentsForBabyOnce(babyId) {
            guard treatment.remindersEnabled, treatment.active, let id = treatment.id else { continue }

            let content = UNMutableNotificationContent()
            content.title = treatment.dose.map { Str.t("treatment_title_dose", treatment.name, $0) }
                ?? treatment.name
            content.body = namesNeeded
                ? Str.t("treatment_notif_text_baby", babyName)
                : Str.t("treatment_notif_text")
            content.sound = .default

            for (slot, minuteOfDay) in treatment.reminderTimes.enumerated() {
                requests += treatmentSlot(
                    treatment: treatment,
                    treatmentId: id,
                    slot: slot,
                    minuteOfDay: minuteOfDay,
                    content: content,
                    now: now
                )
            }
        }
        return requests
    }

    /// One reminder time of one treatment.
    ///
    /// A daily course is a single repeating calendar trigger: iOS keeps firing it
    /// and it never needs topping up. Anything on a longer cycle cannot be
    /// expressed that way — there is no "every third day" trigger — so a bounded
    /// run of one-shots is scheduled instead, and the next foreground extends it.
    private static func treatmentSlot(
        treatment: Treatment,
        treatmentId: Int64,
        slot: Int,
        minuteOfDay: Int,
        content: UNNotificationContent,
        now: Int64
    ) -> [UNNotificationRequest] {
        let identifier = "\(prefix)treatment.\(treatmentId).\(slot)"

        if treatment.intervalDays <= 1, treatment.endDate == nil {
            var parts = DateComponents()
            parts.hour = minuteOfDay / 60
            parts.minute = minuteOfDay % 60
            return [
                UNNotificationRequest(
                    identifier: identifier,
                    content: content,
                    trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: true)
                )
            ]
        }

        var requests: [UNNotificationRequest] = []
        var after = now
        for occurrence in 0..<occurrencesAhead {
            guard let trigger = nextTreatmentTrigger(
                treatment: treatment,
                minuteOfDay: minuteOfDay,
                after: after
            ) else { break }
            requests.append(
                request(id: "\(identifier).\(occurrence)", content: content, at: trigger, now: now)
            )
            after = trigger
        }
        return requests
    }

    // MARK: - Plumbing

    /// A one-shot at an absolute moment.
    ///
    /// By interval rather than by date components: the moment was already
    /// computed against the device's calendar in `Reminders.swift`, and handing
    /// iOS the components again would be asking a second calendar the same
    /// question.
    private static func request(
        id: String,
        content: UNNotificationContent,
        at trigger: Int64,
        now: Int64
    ) -> UNNotificationRequest {
        // At least a second away: iOS rejects a non-positive interval, and the
        // caller has already refused anything in the past.
        let seconds = max(1, Double(trigger - now) / 1000)
        return UNNotificationRequest(
            identifier: id,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        )
    }

    /// Which window the heads-up is about, as the sentence names it — "around 3
    /// weeks", "around 6 months". From `growthSpurtAgeLabel` in
    /// `ui/common/GrowthSpurts.kt`.
    private static func spurtWindowLabel(birthDate: Int64, at trigger: Int64) -> String {
        let ageDays = CalendarDay(millis: birthDate).days(until: CalendarDay(millis: trigger))
        guard let window = growthSpurtWindows.first(where: { $0.startDay == ageDays })
        else { return "" }

        if let weeks = window.approxWeeks { return Str.t("growth_spurt_weeks", weeks) }
        if let months = window.approxMonths { return Str.t("growth_spurt_months", months) }
        return ""
    }
}
