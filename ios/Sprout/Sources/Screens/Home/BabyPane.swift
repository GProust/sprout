import SproutData
import SwiftUI

/// Everything about one baby that is not a history list: how long since each of
/// the three things, a feed you can start from here, the log shortcuts, and
/// today's totals.
///
/// **Shared deliberately.** With one baby the dashboard shows this in place, so
/// nothing is a tap further than it used to be; with two or more the dashboard
/// shows ``BabyCardView`` summaries and this becomes the baby's own tab. Keeping
/// it one view is what makes those two arrangements the same screen.
struct BabyPane<Header: View>: View {
    let summary: BabySummary
    let tracksWellbeing: Bool
    let now: Int64
    /// Every feed running on this phone; this baby's, if any, replaces the
    /// start buttons.
    let sessions: [NursingSession]
    let onFeed: (BreastSide) -> Void
    let onOpen: (LogDestination) -> Void
    let onGiveMedicine: (MedicineWatch) -> Void
    let onDismissMedicine: (MedicineWatch) -> Void
    var onShareRecord: (() -> Void)?
    @ViewBuilder var header: () -> Header

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.regular) {
            header()

            SinceChips(summary: summary, now: now)
            FeedOrRunning(summary: summary, sessions: sessions, onFeed: onFeed)

            // Under the feed buttons, and above the log grid the medicine would
            // otherwise be three taps down in. Feeding is what this screen is
            // opened for; a wait that is running is what it is opened for a few
            // days a year, and it draws nothing at all the rest of the time
            // (BDR-16).
            MedicineWatchCard(
                watches: summary.medicines,
                now: now,
                onGive: onGiveMedicine,
                onDismiss: onDismissMedicine,
                onOpen: { onOpen(.medicines) }
            )

            SectionLabel(Str.t("home_log"))
            LogGrid(tracksWellbeing: tracksWellbeing, onOpen: onOpen)

            SectionLabel(Str.t("home_today"))
            TodayRow(summary: summary)

            Button {
                onOpen(.stats)
            } label: {
                Label(Str.t("home_see_stats"), systemImage: "chart.bar.fill")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.snug)
            }
            .buttonStyle(.bordered)

            // The way out to the doctor's record lives here rather than on a top
            // bar, because this pane is the one thing both arrangements have: a
            // family with one baby never opens the baby's own tab (BDR-0009),
            // and an action offered only there is one half the users cannot
            // reach.
            if let onShareRecord {
                Button(action: onShareRecord) {
                    Label(Str.t("report_screen_title"), systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.snug)
                }
                .buttonStyle(.bordered)
            }
        }
    }
}

/// One baby's line on a household dashboard: the same answers as ``BabyPane``,
/// minus the log grid, plus a way in to the full thing.
///
/// The feed button lives on the card rather than anywhere shared, because which
/// baby it belongs to has to be the card you touched — not a selection made on
/// another screen.
struct BabyCardView: View {
    let summary: BabySummary
    let now: Int64
    /// Every feed running on this phone; this baby's, if any, replaces the
    /// start buttons.
    let sessions: [NursingSession]
    let onOpen: () -> Void
    let onFeed: (BreastSide) -> Void
    let onGiveMedicine: (MedicineWatch) -> Void
    let onDismissMedicine: (MedicineWatch) -> Void
    let onOpenMedicines: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.snug) {
            Button(action: onOpen) {
                HStack(alignment: .firstTextBaseline, spacing: Spacing.tight) {
                    Text(summary.baby.name)
                        .font(.title3.weight(.bold))
                        .foregroundStyle(SproutColor.onSurface)
                    Text(SproutFormat.age(birthDate: summary.baby.birthDate, now: now).text)
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Str.t("cd_open_baby", summary.baby.name))

            SinceChips(summary: summary, now: now)
            FeedOrRunning(summary: summary, sessions: sessions, onFeed: onFeed)
            MedicineWatchCard(
                watches: summary.medicines,
                now: now,
                onGive: onGiveMedicine,
                onDismiss: onDismissMedicine,
                onOpen: onOpenMedicines
            )
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

/// "Fed 2 h ago · Slept 40 min ago · Nappy 1 h ago" — the questions the app is
/// opened to answer, in the order they get asked.
struct SinceChips: View {
    let summary: BabySummary
    let now: Int64

    var body: some View {
        FlowLayout(spacing: Spacing.tight) {
            if let fed = summary.lastFeed {
                chip(Str.t("home_chip_fed", SproutFormat.relative(fed, now: now).text))
            }
            if let slept = summary.lastSleep {
                chip(Str.t("home_chip_slept", SproutFormat.relative(slept, now: now).text))
            }
            if let nappy = summary.lastDiaper {
                chip(Str.t("home_chip_nappy", SproutFormat.relative(nappy, now: now).text))
            }
        }
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .padding(.horizontal, Spacing.snug)
            .padding(.vertical, Spacing.hairline + 2)
            .background(SproutColor.background, in: Capsule())
            .foregroundStyle(SproutColor.onSurfaceVariant)
    }
}

/// A baby's feed buttons — or, while that baby is feeding, the feed itself.
///
/// A baby has one mouth, so a baby whose feed is running is offered that feed
/// back rather than a second one; the other twin's card still starts theirs, on
/// the breast nobody is at (BDR-23).
struct FeedOrRunning: View {
    let summary: BabySummary
    let sessions: [NursingSession]
    let onFeed: (BreastSide) -> Void

    var body: some View {
        if let running = sessions.runningFor(summary.baby.uid) {
            // The same path as starting: the store hands back the feed already
            // running, and the timer shows it rather than starting another.
            RunningFeedButton(session: running) { onFeed(running.currentSide) }
        } else {
            QuickFeed(
                next: sideForNextFeed(sessions, babyUid: summary.baby.uid, fromHistory: summary.nextSide),
                onFeed: onFeed
            )
        }
    }
}

/// "Feeding · Left   4:12" on a baby's own card, one tap back to the timer.
///
/// Time at the breast, as everywhere (BDR-17): the clock stops on a break. It is
/// the one thing on the card with a second hand, so it keeps its own.
private struct RunningFeedButton: View {
    let session: NursingSession
    let onOpen: () -> Void

    @State private var now = Clock.millis

    var body: some View {
        Button(action: onOpen) {
            HStack {
                Image(systemName: session.isPaused ? "pause.circle.fill" : "drop.fill")
                Text(Str.t("home_live_nursing", session.stateLabel))
                    .font(.callout.weight(.medium))
                Spacer()
                Text(SproutFormat.clock(millis: session.nursedMs(at: now)))
                    .font(.callout.weight(.semibold))
                    .monospacedDigit()
            }
            .padding(.horizontal, Spacing.regular)
            .padding(.vertical, Spacing.snug)
            .frame(maxWidth: .infinity)
            .background(SproutColor.primaryContainer, in: RoundedRectangle(cornerRadius: Radius.control))
            .foregroundStyle(SproutColor.onPrimaryContainer)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("baby-running-feed")
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                now = Clock.millis
            }
        }
    }
}

/// Start a breastfeed without going anywhere first.
///
/// When the app can say which side comes next it leads with that one and offers
/// the other quietly beside it; when it cannot — nothing nursed within a day, or
/// a session that settles nothing — both are offered equally rather than
/// guessing.
struct QuickFeed: View {
    let next: BreastSide?
    let onFeed: (BreastSide) -> Void

    var body: some View {
        HStack(spacing: Spacing.tight) {
            switch next {
            case .LEFT, .RIGHT:
                let suggested = next!
                let other: BreastSide = suggested == .LEFT ? .RIGHT : .LEFT
                Button(startLabel(suggested)) { onFeed(suggested) }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                Button(other.label) { onFeed(other) }
                    .buttonStyle(.bordered)
            default:
                Button(Str.t("feeding_start_left")) { onFeed(.LEFT) }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                Button(Str.t("feeding_start_right")) { onFeed(.RIGHT) }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func startLabel(_ side: BreastSide) -> String {
        side == .RIGHT ? Str.t("feeding_start_right") : Str.t("feeding_start_left")
    }
}

/// Where a log tile goes.
///
/// Not a raw-value enum, because one case carries something: **the report names
/// the baby it is for.** Android routes it as `report/{babyId}` for the same
/// reason, and its own comment says why — "never for whichever is active". The
/// export is reached from one baby's page, and a menu left on another name must
/// not be able to send the wrong child's record.
enum LogDestination: Hashable {
    case feeding, pumping, sleep, diaper, growth, treatments, medicines, foods, wellbeing
    case stats, checkIn
    case report(Int64)
    /// Not logs — the two the dashboard's toolbar opens, and the sync screen
    /// behind Settings. They travel through the same push so there is one way
    /// into a screen rather than two.
    case profile, settings, sync

    /// The tile's accessibility identifier, and the only handle the screenshot
    /// run has on it. An identifier and not the visible label, because the
    /// capture walks the same grid in seven languages.
    var tileIdentifier: String { "log-tile-\(name)" }

    private var name: String {
        switch self {
        case .feeding: return "feeding"
        case .pumping: return "pumping"
        case .sleep: return "sleep"
        case .diaper: return "diaper"
        case .growth: return "growth"
        case .treatments: return "treatments"
        case .medicines: return "medicines"
        case .foods: return "foods"
        case .wellbeing: return "wellbeing"
        case .stats: return "stats"
        case .checkIn: return "checkIn"
        case .report: return "report"
        case .profile: return "profile"
        case .settings: return "settings"
        case .sync: return "sync"
        }
    }
}

/// The seven baby logs plus the parent's two, as equals.
///
/// All of them sit here rather than in the tab bar (BDR-0010): a Material
/// navigation bar holds five, Home takes one of those seats, and the arithmetic
/// stranded treatments in a list next to pumping with nothing to say why. There
/// is no seat to lose in a grid.
struct LogGrid: View {
    let tracksWellbeing: Bool
    let onOpen: (LogDestination) -> Void

    /// A named type rather than a tuple: `ForEach` cannot destructure a tuple
    /// element into closure parameters, and `\.2` is not a key path Swift will
    /// form.
    private struct Tile: Identifiable {
        let label: String
        let symbol: String
        let destination: LogDestination

        var id: LogDestination { destination }
    }

    private var tiles: [Tile] {
        var tiles: [Tile] = [
            Tile(label: Str.t("nav_feed"), symbol: "drop.fill", destination: .feeding),
            Tile(label: Str.t("screen_pumping"), symbol: "drop.triangle.fill", destination: .pumping),
            Tile(label: Str.t("nav_sleep"), symbol: "moon.zzz.fill", destination: .sleep),
            Tile(label: Str.t("nav_diaper"), symbol: "figure.child", destination: .diaper),
            Tile(label: Str.t("nav_growth"), symbol: "ruler", destination: .growth),
            Tile(label: Str.t("screen_treatments"), symbol: "pills.fill", destination: .treatments),
            Tile(label: Str.t("screen_medicines"), symbol: "cross.vial.fill", destination: .medicines),
            // After the others rather than beside Feed, so no tile a parent
            // reaches for by position has moved; and for every baby, because
            // when diversification starts is not the app's to decide (BDR-20).
            Tile(label: Str.t("screen_foods"), symbol: "fork.knife", destination: .foods),
        ]
        // Dropped for a parent who has turned their own tracking off; the
        // history stays, untouched.
        if tracksWellbeing {
            tiles.append(Tile(label: Str.t("screen_wellbeing"), symbol: "heart.fill", destination: .wellbeing))
        }
        return tiles
    }

    var body: some View {
        // Three columns, fixed. A short last row keeps its tiles the same width
        // as a full one's rather than letting three columns become one wide
        // button.
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: Spacing.tight), count: 3),
            spacing: Spacing.tight
        ) {
            ForEach(tiles) { tile in
                Button {
                    onOpen(tile.destination)
                } label: {
                    VStack(spacing: Spacing.hairline + 2) {
                        Image(systemName: tile.symbol)
                            .font(.title3)
                            .foregroundStyle(SproutColor.primary)
                        Text(tile.label)
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurface)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.snug + 2)
                    .background(
                        RoundedRectangle(cornerRadius: Radius.control)
                            .stroke(SproutColor.outline.opacity(0.4), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(tile.destination.tileIdentifier)
            }
        }
    }
}

private struct TodayRow: View {
    let summary: BabySummary

    var body: some View {
        HStack(spacing: Spacing.snug) {
            StatCard(
                label: Str.t("stat_feeds"),
                value: "\(summary.feedsToday)",
                systemImage: "drop.fill"
            )
            StatCard(
                label: Str.t("stat_sleep"),
                value: SproutFormat.duration(millis: summary.sleepTodayMs).text,
                systemImage: "moon.zzz.fill"
            )
            StatCard(
                label: Str.t("stat_diapers"),
                value: "\(summary.diapersToday)",
                systemImage: "figure.child"
            )
        }
    }
}

/// The gentle growth-spurt note (BDR-5), from `GrowthSpurtNote` in
/// `ui/home/BabyPane.kt`: shown while a baby is in — or a few days from — one of
/// the typical periods, and gone again on its own. Informational only; every
/// baby is different, so there is nothing to configure, log or dismiss.
struct GrowthSpurtNote: View {
    let window: GrowthSpurtWindow
    let startsSoon: Bool

    /// The note for a baby born at `birthDate`, or nil while no period is near.
    init?(birthDate: Int64, now: Int64) {
        let ageDays = ageInDays(birthDate: birthDate, now: now)
        if let current = currentGrowthSpurt(ageDays: ageDays) {
            window = current
            startsSoon = false
        } else if let upcoming = upcomingGrowthSpurt(ageDays: ageDays) {
            window = upcoming
            startsSoon = true
        } else {
            return nil
        }
    }

    var body: some View {
        HStack(spacing: Spacing.snug) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .foregroundStyle(SproutColor.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(startsSoon ? Str.t("growth_spurt_soon_title") : Str.t("growth_spurt_now_title"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(SproutColor.onSurface)
                Text(
                    startsSoon
                        ? Str.t("growth_spurt_soon_body", ageLabel)
                        : Str.t("growth_spurt_now_body", ageLabel)
                )
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            }
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surfaceVariant, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .combine)
    }

    /// "around 3 weeks", declined by each language from the window's number.
    private var ageLabel: String {
        if let weeks = window.approxWeeks { return Str.t("growth_spurt_weeks", weeks) }
        if let months = window.approxMonths { return Str.t("growth_spurt_months", months) }
        return ""
    }
}
