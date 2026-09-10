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
    let onFeed: (BreastSide) -> Void
    let onOpen: (LogDestination) -> Void
    var onShareRecord: (() -> Void)?
    @ViewBuilder var header: () -> Header

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.regular) {
            header()

            SinceChips(summary: summary, now: now)
            QuickFeed(next: summary.nextSide, onFeed: onFeed)

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
    let onOpen: () -> Void
    let onFeed: (BreastSide) -> Void

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
            QuickFeed(next: summary.nextSide, onFeed: onFeed)
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
enum LogDestination: String, Hashable {
    case feeding, pumping, sleep, diaper, growth, treatments, wellbeing
    case stats, checkIn, report
    /// Not logs — the two the dashboard's toolbar opens, and the sync screen
    /// behind Settings. They travel through the same push so there is one way
    /// into a screen rather than two.
    case profile, settings, sync

    /// The tile's accessibility identifier, and the only handle the screenshot
    /// run has on it. An identifier and not the visible label, because the
    /// capture walks the same grid in seven languages.
    var tileIdentifier: String { "log-tile-\(rawValue)" }
}

/// The five baby logs plus the parent's two, as equals.
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
