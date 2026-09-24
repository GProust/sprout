import SproutData
import SwiftUI

/// Feeds, from `ui/feeding/FeedingScreen.kt` — the busiest screen in the app.
@Observable
@MainActor
final class FeedingViewModel {
    var feedings: [Feeding] = []

    private let repository: SproutRepository
    private let store = NursingSessionStore.shared

    init(repository: SproutRepository) {
        self.repository = repository
    }

    /// The live session, read straight from the shared store rather than copied
    /// out of it. See ``NursingSessionStore`` — a private copy per screen is how
    /// the same breastfeed used to be saved more than once.
    var nursing: NursingSession? { store.session }

    func observeFeedings() async {
        await observe(repository.feedings) { [weak self] in self?.feedings = $0 }
    }

    func add(_ feeding: Feeding) { try? repository.addFeeding(feeding) }
    func delete(_ feeding: Feeding) { try? repository.deleteFeeding(feeding) }

    /// Join two breastfeeds saved apart into the one feed they were (BDR-19).
    func join(_ earlier: Feeding, _ later: Feeding) {
        try? repository.joinBreastfeeds(earlier, later)
    }

    /// Which cards can be joined to the feed below them: the later of two
    /// neighbouring breastfeeds a short break apart, keyed by its uid.
    var joinOffers: [String: Feeding] { breastfeedJoinOffers(feedings) }

    /// Begin timing on `side`. A session already running is left alone: two ways
    /// into the timer opening at once is not a reason to time the feed twice.
    func startNursing(on side: BreastSide) {
        let now = Clock.millis
        store.startIfIdle(
            NursingSession(sessionStart: now, currentSide: side, segmentStart: now)
        )
    }

    func switchBreast() { store.switchBreast(at: Clock.millis) }

    /// Bank the breast being nursed and start a break — the burp between the
    /// sides, the nappy halfway through (BDR-17).
    func pauseNursing() { store.pause(at: Clock.millis) }

    /// Come back from a break, on whichever breast the feed carries on with.
    func resumeNursing(on side: BreastSide) { store.resume(on: side, at: Clock.millis) }

    /// Finish the session and persist it as a feed.
    ///
    /// The session is *taken* from the store before anything is saved, so that
    /// of everything which might still be showing this timer — a second copy of
    /// the nursing screen, a second tap on Save — only one is ever handed
    /// something to log.
    func stopNursing(notes: String = "") {
        guard let session = store.consume() else { return }
        let now = Clock.millis
        // On a break there is no stretch in progress, so what was nursed is
        // exactly what was banked.
        let all = session.allSegments(endingAt: now)

        let left = all.filter { $0.side == .LEFT }.reduce(Int64(0)) { $0 + $1.durationMs }
        let right = all.filter { $0.side == .RIGHT }.reduce(Int64(0)) { $0 + $1.durationMs }

        let side: BreastSide = {
            if left > 0 && right > 0 { return .BOTH }
            if right > 0 { return .RIGHT }
            return .LEFT
        }()

        add(
            Feeding(
                type: .BREAST,
                side: side,
                startTime: session.sessionStart,
                // The feed ended when the last stretch did, not when Save was
                // tapped: a session saved from a break would otherwise carry
                // the whole break as feeding time it never was.
                endTime: all.last?.endTime ?? now,
                leftDurationMs: left > 0 ? left : nil,
                rightDurationMs: right > 0 ? right : nil,
                segments: all,
                notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        )
    }

    /// Discard the running session without saving it.
    func cancelNursing() { store.clear() }

    var byDay: [(day: Int64, entries: [Feeding])] {
        Dictionary(grouping: feedings) { SproutFormat.startOfDay($0.startTime) }
            .map { (day: $0.key, entries: $0.value) }
            .sorted { $0.day > $1.day }
    }
}

struct FeedingScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: FeedingViewModel?
    @State private var adding = false
    @State private var pendingDelete: Feeding?
    /// The two feeds awaiting a "yes, join them". Joining can't be taken back
    /// either, so it asks the way a delete does.
    @State private var pendingJoin: FeedJoin?
    @State private var showingTimer = false

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_feeding"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? FeedingViewModel(repository: sprout.repository)
            self.model = model
            await model.observeFeedings()
        }
    }

    @ViewBuilder
    private func content(_ model: FeedingViewModel) -> some View {
        let joinOffers = model.joinOffers
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    // The two big buttons come first: at 3 a.m. the thing being
                    // done is starting a feed, not reading the history.
                    if let session = model.nursing {
                        RunningSessionBar(session: session) { showingTimer = true }
                    } else {
                        StartNursingButtons { side in
                            model.startNursing(on: side)
                            showingTimer = true
                        }
                    }

                    if model.feedings.isEmpty {
                        EmptyHint(Str.t("feeding_empty"))
                    }
                    ForEach(model.byDay, id: \.day) { group in
                        DayHeader(dayStartMillis: group.day, now: Clock.millis)
                        ForEach(group.entries) { entry in
                            FeedCard(
                                entry: entry,
                                onJoin: joinOffers[entry.uid].map { (earlier: Feeding) -> (() -> Void) in
                                    { pendingJoin = FeedJoin(earlier: earlier, later: entry) }
                                }
                            ) { pendingDelete = entry }
                        }
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("feeding_log_title")) { adding = true }
                .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(isPresented: $adding) {
            FeedingForm { model.add($0); adding = false }
        }
        .fullScreenCover(isPresented: $showingTimer) {
            NursingTimerScreen(model: model)
        }
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            if let entry = pendingDelete { model.delete(entry) }
            pendingDelete = nil
        }
        .alert(
            Str.t("feeding_join_title"),
            isPresented: Binding(get: { pendingJoin != nil }, set: { if !$0 { pendingJoin = nil } }),
            presenting: pendingJoin
        ) { join in
            Button(Str.t("action_cancel"), role: .cancel) {}
            Button(Str.t("feeding_join_confirm")) { model.join(join.earlier, join.later) }
                .accessibilityIdentifier("feeding-join-confirm")
        } message: { join in
            Text(join.message)
        }
    }
}

// MARK: - Joining two feeds

/// Two breastfeeds saved apart, on their way to being the one feed they were
/// (BDR-19) — held while the confirmation is up.
private struct FeedJoin {
    let earlier: Feeding
    let later: Feeding

    /// Names both feeds by their start times and the gap between them, then
    /// shows the joined feed's timeline, so what the parent agrees to is
    /// exactly what they will get. An alert can only hold text, so the
    /// timeline is written out the way the card's details write it.
    var message: String {
        let gap = breastfeedJoinGap(earlier, later) ?? 0
        let sentence = gap > 0
            ? Str.t(
                "feeding_join_body",
                SproutDateStyle.time(earlier.startTime),
                SproutDateStyle.time(later.startTime),
                SproutFormat.duration(millis: gap).text
            )
            : Str.t(
                "feeding_join_body_no_gap",
                SproutDateStyle.time(earlier.startTime),
                SproutDateStyle.time(later.startTime)
            )
        let timeline = timelineLines(joinedBreastfeed(earlier, later).nursingSegments)
        return ([sentence, ""] + timeline).joined(separator: "\n")
    }
}

/// The quiet way into a join: small, in the muted colour of the rest of the
/// card, and never anything that suggests the two feeds *should* be one. Only
/// the parent who was there knows that.
private struct JoinWithPreviousButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(Str.t("feeding_join"), systemImage: "link")
                .font(.footnote)
        }
        .buttonStyle(.plain)
        .foregroundStyle(SproutColor.onSurfaceVariant)
        .accessibilityIdentifier("feeding-join")
    }
}

// MARK: - The history card

private struct FeedCard: View {
    let entry: Feeding
    /// Set when the feed below can be joined to this one (BDR-19).
    var onJoin: (() -> Void)?
    let onDelete: () -> Void

    // Each branch hands `EntryCard` a concrete type, because it decides whether
    // to draw the Details row from the *type* of what it is given: an `if`
    // inside one builder is never `EmptyView`, and put a Details button on
    // every bottle with nothing behind it.
    var body: some View {
        let segments = entry.nursingSegments
        if segments.isEmpty {
            card(details: { EmptyView() }, action: { EmptyView() })
        } else if let onJoin {
            // Beside Details, in the row a joinable card already has — so
            // nothing grows to make room for it.
            card(
                details: { SegmentTimeline(segments: segments) },
                action: { JoinWithPreviousButton(action: onJoin) }
            )
        } else {
            card(details: { SegmentTimeline(segments: segments) }, action: { EmptyView() })
        }
    }

    private func card<Details: View, Action: View>(
        @ViewBuilder details: @escaping () -> Details,
        @ViewBuilder action: @escaping () -> Action
    ) -> some View {
        EntryCard(
            title: entry.title,
            subtitle: entry.subtitle,
            meta: SproutDateStyle.time(entry.startTime),
            systemImage: entry.type.systemImage,
            onDelete: onDelete,
            details: details,
            action: action
        )
    }
}

/// A finished breastfeed stretch by stretch, with its breaks where they happened.
private struct SegmentTimeline: View {
    let segments: [NursingSegment]

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.hairline) {
            ForEach(Array(segments.enumerated()), id: \.offset) { index, segment in
                // The break is the gap the stretches leave; nothing records one
                // of its own, so a feed logged before breaks existed shows none.
                let previousEnd = index == 0 ? segment.startTime : segments[index - 1].endTime
                if segment.startTime > previousEnd {
                    TimelineLine(
                        label: Str.t("feeding_paused"),
                        millis: segment.startTime - previousEnd,
                        start: previousEnd,
                        end: segment.startTime
                    )
                }
                TimelineLine(
                    label: segment.side.label,
                    millis: segment.durationMs,
                    start: segment.startTime,
                    end: segment.endTime
                )
            }
        }
    }
}

/// The same timeline as lines of text, for where only text will go.
private func timelineLines(_ segments: [NursingSegment]) -> [String] {
    var lines: [String] = []
    for (index, segment) in segments.enumerated() {
        let previousEnd = index == 0 ? segment.startTime : segments[index - 1].endTime
        if segment.startTime > previousEnd {
            lines.append(
                timelineText(
                    label: Str.t("feeding_paused"),
                    millis: segment.startTime - previousEnd,
                    start: previousEnd,
                    end: segment.startTime
                )
            )
        }
        lines.append(
            timelineText(
                label: segment.side.label,
                millis: segment.durationMs,
                start: segment.startTime,
                end: segment.endTime
            )
        )
    }
    return lines
}

/// "Left 8m · 10:00–10:08".
private func timelineText(label: String, millis: Int64, start: Int64, end: Int64) -> String {
    Str.t(
        "feeding_segment_line",
        label,
        SproutFormat.duration(millis: millis).text,
        "\(SproutDateStyle.time(start))–\(SproutDateStyle.time(end))"
    )
}

/// One line of a session's timeline: a stretch at a breast, or a break between
/// two of them. "Left 8m · 10:00–10:08".
private struct TimelineLine: View {
    let label: String
    let millis: Int64
    let start: Int64
    let end: Int64

    var body: some View {
        Text(timelineText(label: label, millis: millis, start: start, end: end))
            .font(.footnote)
            .foregroundStyle(SproutColor.onSurfaceVariant)
    }
}

extension FeedType {
    var systemImage: String {
        switch self {
        case .BREAST: return "heart.fill"
        case .BOTTLE: return "waterbottle.fill"
        case .SOLID: return "fork.knife"
        }
    }
}

extension Feeding {
    var title: String {
        switch type {
        case .BREAST:
            guard let side else { return Str.t("feed_type_breast") }
            return "\(Str.t("feed_type_breast")) · \(side.label)"
        case .BOTTLE:
            guard let amountMl else { return Str.t("feed_type_bottle") }
            return Str.t("feeding_amount_ml", amountMl)
        case .SOLID:
            guard let amountGrams else { return Str.t("feed_type_solid") }
            return Str.t("feeding_amount_g", amountGrams)
        }
    }

    var subtitle: String {
        var parts: [String] = []
        if type == .BREAST {
            // Time at the breast, which is not the wall clock the feed spanned:
            // the burp in the middle is not something the baby drank (BDR-17).
            let nursed = breastfeedMillis(self)
            if nursed > 0 { parts.append(SproutFormat.duration(millis: nursed).text) }
            let paused = breastfeedPausedMillis(nursingSegments)
            if paused > 0 {
                parts.append(
                    Str.t("feeding_paused_total", SproutFormat.duration(millis: paused).text)
                )
            }
        } else if let end = endTime {
            parts.append(SproutFormat.duration(millis: end - startTime).text)
        }
        if let notes = notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty {
            parts.append(notes)
        }
        return parts.joined(separator: Str.t("feeding_detail_separator"))
    }
}

// MARK: - Starting and running

private struct StartNursingButtons: View {
    let onStart: (BreastSide) -> Void

    var body: some View {
        HStack(spacing: Spacing.snug) {
            ForEach([BreastSide.LEFT, BreastSide.RIGHT], id: \.self) { side in
                Button {
                    onStart(side)
                } label: {
                    Text(side == .LEFT ? Str.t("feeding_start_left") : Str.t("feeding_start_right"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.regular)
                        .background(SproutColor.primaryContainer, in: RoundedRectangle(cornerRadius: Radius.card))
                        .foregroundStyle(SproutColor.onPrimaryContainer)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Shown on the list while a session is running, so the timer is never more
/// than one tap away from wherever the parent ended up.
private struct RunningSessionBar: View {
    let session: NursingSession
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack {
                Image(systemName: session.isPaused ? "pause.circle" : "timer")
                Text(
                    session.isPaused
                        ? Str.t("feeding_paused")
                        : (session.currentSide == .LEFT
                            ? Str.t("feeding_on_left")
                            : Str.t("feeding_on_right"))
                )
                .font(.body.weight(.medium))
                Spacer()
                Text(Str.t("feeding_resume"))
                    .font(.footnote.weight(.medium))
            }
            .padding(Spacing.regular)
            .background(SproutColor.primary, in: RoundedRectangle(cornerRadius: Radius.card))
            .foregroundStyle(SproutColor.onPrimary)
        }
        .buttonStyle(.plain)
    }
}

/// The live timer.
///
/// The one screen in the app that genuinely needs a second hand — a parent
/// watches this while feeding, and a clock that only moved every minute would
/// look broken.
private struct NursingTimerScreen: View {
    let model: FeedingViewModel

    @Environment(\.dismiss) private var dismiss
    @State private var now = Clock.millis
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: Spacing.loose) {
                if let session = model.nursing {
                    Text(
                        session.isPaused
                            ? Str.t("feeding_paused_for", SproutFormat.clock(millis: session.pausedMs(at: now)))
                            : (session.currentSide == .LEFT
                                ? Str.t("feeding_on_left")
                                : Str.t("feeding_on_right"))
                    )
                    .font(.title3.weight(.medium))
                    .foregroundStyle(SproutColor.onSurfaceVariant)

                    // Time at the breast, not time since the feed began: the
                    // clock stops while the break runs (BDR-17).
                    Text(SproutFormat.clock(millis: session.nursedMs(at: now)))
                        .font(.system(size: 64, weight: .light, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(SproutColor.onSurface)
                        // Read at arm's length in the dark, and the digits must
                        // not shuffle as the minutes tick over.
                        .contentTransition(.numericText())

                    if !session.isPaused {
                        Text(
                            Str.t(
                                "feeding_side_time",
                                session.currentSide.label,
                                SproutFormat.clock(millis: now - session.segmentStart)
                            )
                        )
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                    }

                    let stretches = session.segments
                    if !stretches.isEmpty || session.isPaused {
                        VStack(alignment: .leading, spacing: Spacing.hairline) {
                            ForEach(Array(stretches.enumerated()), id: \.offset) { index, segment in
                                // A break is shown where it happened rather
                                // than as a total at the bottom: what a parent
                                // wants back is *when* the last one was.
                                let previousEnd = index == 0 ? segment.startTime : stretches[index - 1].endTime
                                if segment.startTime > previousEnd {
                                    TimelineLine(
                                        label: Str.t("feeding_paused"),
                                        millis: segment.startTime - previousEnd,
                                        start: previousEnd,
                                        end: segment.startTime
                                    )
                                }
                                TimelineLine(
                                    label: segment.side.label,
                                    millis: segment.durationMs,
                                    start: segment.startTime,
                                    end: segment.endTime
                                )
                            }
                            if let pausedAt = session.pausedAt {
                                TimelineLine(
                                    label: Str.t("feeding_paused"),
                                    millis: session.pausedMs(at: now),
                                    start: pausedAt,
                                    end: now
                                )
                            }
                        }
                    }

                    Spacer()

                    if session.isPaused {
                        // Resuming names the breast rather than saying
                        // "resume", because the whole point of the break is
                        // that the next stretch is often the other side — and
                        // after a burp nobody remembers which.
                        HStack(spacing: Spacing.snug) {
                            ForEach([BreastSide.LEFT, BreastSide.RIGHT], id: \.self) { side in
                                Button {
                                    model.resumeNursing(on: side)
                                } label: {
                                    Text(
                                        side == .LEFT
                                            ? Str.t("feeding_resume_left")
                                            : Str.t("feeding_resume_right")
                                    )
                                    .font(.body.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, Spacing.regular)
                                    .background(SproutColor.primaryContainer, in: Capsule())
                                    .foregroundStyle(SproutColor.onPrimaryContainer)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else {
                        HStack(spacing: Spacing.snug) {
                            Button {
                                model.pauseNursing()
                            } label: {
                                Text(Str.t("feeding_pause"))
                                    .font(.body.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, Spacing.regular)
                                    .background(SproutColor.surfaceVariant, in: Capsule())
                                    .foregroundStyle(SproutColor.onSurfaceVariant)
                            }
                            .buttonStyle(.plain)

                            Button {
                                model.switchBreast()
                            } label: {
                                Text(
                                    session.currentSide == .LEFT
                                        ? Str.t("feeding_switch_to_right")
                                        : Str.t("feeding_switch_to_left")
                                )
                                .font(.body.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, Spacing.regular)
                                .background(SproutColor.primaryContainer, in: Capsule())
                                .foregroundStyle(SproutColor.onPrimaryContainer)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    NotesField(text: $notes)

                    Button {
                        model.stopNursing(notes: notes)
                        dismiss()
                    } label: {
                        Text(Str.t("feeding_stop_save"))
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, Spacing.regular)
                            .background(SproutColor.primary, in: Capsule())
                            .foregroundStyle(SproutColor.onPrimary)
                    }
                    .buttonStyle(.plain)
                } else {
                    // The session ended elsewhere — another copy of this screen,
                    // or the widget. Nothing left to time, and nothing to save.
                    EmptyHint(Str.t("feeding_empty"))
                }
            }
            .padding(Spacing.regular)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .sproutStyle()
            .navigationTitle(Str.t("feeding_breastfeeding"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_close")) { dismiss() }
                }
            }
            .task {
                // One second, and only while this screen is up.
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    now = Clock.millis
                }
            }
        }
    }
}

// MARK: - The manual form

private struct FeedingForm: View {
    let onAdd: (Feeding) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var type: FeedType = .BOTTLE
    @State private var side: BreastSide?
    @State private var amount = ""
    @State private var minutes = ""
    @State private var start = Clock.millis
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FieldLabel(Str.t("field_type"))
                    ChoiceChips(
                        options: FeedType.allCases,
                        selection: Binding(get: { type }, set: { type = $0 ?? type }),
                        label: \.label
                    )
                }

                Section {
                    switch type {
                    case .BREAST:
                        FieldLabel(Str.t("field_side"))
                        ChoiceChips(
                            options: BreastSide.allCases,
                            selection: $side,
                            label: \.label,
                            allowsDeselection: true
                        )
                        NumberField(label: Str.t("feeding_length"), text: $minutes, suffix: "min")
                    case .BOTTLE:
                        NumberField(label: Str.t("field_amount"), text: $amount, suffix: "ml")
                    case .SOLID:
                        NumberField(label: Str.t("field_amount_solid"), text: $amount, suffix: "g")
                    }
                }

                Section {
                    DateTimeField(label: Str.t("field_time"), millis: $start)
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t("feeding_log_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("feeding_add")) { onAdd(built) }
                }
            }
        }
    }

    private var built: Feeding {
        let length = Int(minutes).map { Int64($0) * 60_000 }
        return Feeding(
            type: type,
            side: type == .BREAST ? side : nil,
            amountMl: type == .BOTTLE ? Int(amount) : nil,
            amountGrams: type == .SOLID ? Int(amount) : nil,
            startTime: start,
            endTime: type == .BREAST ? length.map { start + $0 } : nil,
            notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }
}
