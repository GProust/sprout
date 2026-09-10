import SproutData
import SwiftUI

// The three log cards. Each is a few averages, one chart, and — once a day is
// tapped — what that day was actually made of.

struct FeedingCard: View {
    let model: StatsViewModel
    @Binding var selectedDay: CalendarDay?

    @State private var series: FeedSeries = .count

    var body: some View {
        StatsCard(title: Str.t("stats_feeding_title"), systemImage: "drop.fill") {
            StatLine(Str.t("stats_feeds_per_day"), decimal(model.averages.feedsPerDay), emphasis: true)
            StatLine(
                Str.t("stats_breast_per_day"),
                countAndAmount(
                    model.averages.breastfeedsPerDay,
                    SproutFormat.duration(millis: model.averages.breastMillisPerDay).text
                )
            )
            StatLine(
                Str.t("stats_bottle_per_day"),
                countAndAmount(
                    model.averages.bottlesPerDay,
                    Str.t("feeding_amount_ml", model.averages.bottleMlPerDay)
                )
            )
            StatLine(
                Str.t("stats_solid_per_day"),
                countAndAmount(
                    model.averages.solidsPerDay,
                    Str.t("feeding_amount_g", model.averages.solidGramsPerDay)
                )
            )

            ChoiceChips(
                options: FeedSeries.allCases,
                selection: Binding(get: { series }, set: { series = $0 ?? series }),
                label: \.label
            )
            .padding(.top, Spacing.snug)

            DayChart(
                days: model.days,
                topLabel: topLabel,
                selectedDay: $selectedDay,
                onMove: { model.moveWindow(by: $0) }
            ) { size in
                Canvas { context, _ in
                    context.drawDayBars(
                        model.days,
                        in: size,
                        value: value,
                        bar: SproutColor.primary,
                        empty: SproutColor.surfaceVariant
                    )
                    context.drawSelection(
                        model.days, in: size, selected: selectedDay, ring: SproutColor.onSurface
                    )
                }
            }

            DayDetail(day: $selectedDay) { day in
                let feeds = feedsOn(model.feedings, day: day)
                if feeds.isEmpty {
                    DetailEmpty(Str.t("stats_detail_no_feeds"))
                } else {
                    ForEach(feeds) { feed in
                        DetailRow(time: SproutDateStyle.time(feed.startTime), text: feedLine(feed))
                    }
                }
            }
        }
    }

    private func value(_ day: DayStats) -> Double {
        switch series {
        case .count: return Double(day.feedCount)
        case .breast: return Double(day.breastMillis) / 3_600_000
        case .bottle: return Double(day.bottleMl)
        case .solid: return Double(day.solidGrams)
        }
    }

    private var topLabel: String {
        switch series {
        case .count:
            return model.days.map(\.feedCount).max().map { String($0) } ?? ""
        case .breast:
            return model.days.map(\.breastMillis).max()
                .map { SproutFormat.duration(millis: $0).text } ?? ""
        case .bottle:
            return model.days.map(\.bottleMl).max().map { Str.t("feeding_amount_ml", $0) } ?? ""
        case .solid:
            return model.days.map(\.solidGrams).max().map { Str.t("feeding_amount_g", $0) } ?? ""
        }
    }

    private func feedLine(_ feed: Feeding) -> String {
        switch feed.type {
        case .BREAST:
            return Str.t("stats_detail_breast", SproutFormat.duration(millis: breastfeedMillis(feed)).text)
        case .BOTTLE:
            guard let ml = feed.amountMl else { return Str.t("stats_detail_bottle_unmeasured") }
            return Str.t("stats_detail_bottle", Str.t("feeding_amount_ml", ml))
        case .SOLID:
            guard let g = feed.amountGrams else { return Str.t("stats_detail_solid_unweighed") }
            return Str.t("stats_detail_solid", Str.t("feeding_amount_g", g))
        }
    }
}

struct SleepCard: View {
    let model: StatsViewModel
    @Binding var selectedDay: CalendarDay?

    var body: some View {
        StatsCard(title: Str.t("stats_sleep_title"), systemImage: "moon.zzz.fill") {
            StatLine(
                Str.t("stats_sleep_per_day"),
                SproutFormat.duration(millis: model.averages.sleepMillisPerDay).text,
                emphasis: true
            )
            StatLine(Str.t("stats_sleeps_per_day"), decimal(model.averages.sleepsPerDay))

            DayChart(
                days: model.days,
                topLabel: model.days.map(\.sleepMillis).max()
                    .map { SproutFormat.duration(millis: $0).text } ?? "",
                selectedDay: $selectedDay,
                onMove: { model.moveWindow(by: $0) }
            ) { size in
                Canvas { context, _ in
                    context.drawDayBars(
                        model.days,
                        in: size,
                        value: { Double($0.sleepMillis) / 3_600_000 },
                        bar: SproutColor.primary,
                        empty: SproutColor.surfaceVariant
                    )
                    context.drawSelection(
                        model.days, in: size, selected: selectedDay, ring: SproutColor.onSurface
                    )
                }
            }

            // Shown only once something has been recorded: a card that asks
            // about fields nobody fills in is a nag, and an empty breakdown says
            // nothing the "times settled" line above hasn't already said.
            if model.breakdown.hasPlaces {
                SleepShares(
                    title: Str.t("stats_sleep_where"),
                    rows: model.breakdown.byPlace.map {
                        ShareRow(label: $0.value.label, count: $0.count, millis: $0.millis,
                                 recorded: $0.value != nil)
                    },
                    total: model.breakdown.totalMillis
                )
            }
            if model.breakdown.hasPositions {
                SleepShares(
                    title: Str.t("stats_sleep_position"),
                    rows: model.breakdown.byPosition.map {
                        ShareRow(
                            label: $0.value?.label ?? Str.t("stats_sleep_not_recorded"),
                            count: $0.count,
                            millis: $0.millis,
                            recorded: $0.value != nil
                        )
                    },
                    total: model.breakdown.totalMillis
                )
            }

            DayDetail(day: $selectedDay) { day in
                let sleeps = sleepsOn(model.sleeps, day: day, now: model.now)
                if sleeps.isEmpty {
                    DetailEmpty(Str.t("stats_detail_no_sleep"))
                } else {
                    ForEach(sleeps) { sleep in
                        DetailRow(time: SproutDateStyle.time(sleep.startTime), text: line(for: sleep, on: day))
                    }
                }
            }
        }
    }

    /// A night belongs to two days; saying how much of it landed here is why it
    /// was split in the first place.
    private func line(for sleep: Sleep, on day: CalendarDay) -> String {
        let end = max(sleep.endTime ?? model.now, sleep.startTime)
        let whole = end - sleep.startTime
        let here = sleepMillisOn(sleep, day: day, now: model.now)

        if here < whole {
            return Str.t(
                "stats_detail_sleep_split",
                SproutDateStyle.time(end),
                SproutFormat.duration(millis: whole).text,
                SproutFormat.duration(millis: here).text
            )
        }
        return Str.t(
            "stats_detail_sleep",
            SproutDateStyle.time(end),
            SproutFormat.duration(millis: whole).text
        )
    }
}

struct DiaperCard: View {
    let model: StatsViewModel
    @Binding var selectedDay: CalendarDay?

    var body: some View {
        StatsCard(title: Str.t("stats_diaper_title"), systemImage: "figure.child") {
            StatLine(Str.t("stats_diapers_per_day"), decimal(model.averages.diapersPerDay), emphasis: true)
            StatLine(Str.t("stats_wet_per_day"), decimal(model.averages.wetPerDay))
            StatLine(Str.t("stats_dirty_per_day"), decimal(model.averages.dirtyPerDay))

            DayChart(
                days: model.days,
                topLabel: model.days.map(\.diaperCount).max().map { String($0) } ?? "",
                selectedDay: $selectedDay,
                onMove: { model.moveWindow(by: $0) }
            ) { size in
                Canvas { context, _ in
                    context.drawStackedDiapers(
                        model.days,
                        in: size,
                        wet: SproutColor.primary,
                        dirty: StatsColor.dirty,
                        empty: SproutColor.surfaceVariant
                    )
                    context.drawSelection(
                        model.days, in: size, selected: selectedDay, ring: SproutColor.onSurface
                    )
                }
            }

            HStack(spacing: 14) {
                LegendKey(label: Str.t("stats_wet_per_day"), colour: SproutColor.primary)
                LegendKey(
                    label: Str.t("stats_diaper_both"),
                    colour: SproutColor.primary,
                    hatchWith: StatsColor.dirty
                )
                LegendKey(label: Str.t("stats_dirty_per_day"), colour: StatsColor.dirty)
            }
            .padding(.top, Spacing.tight)

            DayDetail(day: $selectedDay) { day in
                let changes = diapersOn(model.diapers, day: day)
                if changes.isEmpty {
                    DetailEmpty(Str.t("stats_detail_no_diapers"))
                } else {
                    ForEach(changes) { change in
                        DetailRow(time: SproutDateStyle.time(change.time), text: diaperLine(change)) {
                            if let colour = change.stoolColor {
                                Circle()
                                    .fill(colour.swatch)
                                    .frame(width: 12, height: 12)
                                Text(colour.label)
                                    .font(.caption2)
                                    .foregroundStyle(SproutColor.onSurfaceVariant)
                                    .padding(.leading, Spacing.hairline)
                            }
                        }
                    }
                }
            }
        }
    }

    private func diaperLine(_ change: Diaper) -> String {
        if change.wet && change.dirty { return Str.t("stats_diaper_both") }
        return change.dirty ? Str.t("stats_dirty_per_day") : Str.t("stats_wet_per_day")
    }
}

// MARK: - The day's detail

struct DayDetail<Content: View>: View {
    @Binding var day: CalendarDay?
    @ViewBuilder let content: (CalendarDay) -> Content

    var body: some View {
        if let day {
            VStack(alignment: .leading, spacing: 0) {
                Divider().padding(.top, Spacing.snug)
                HStack {
                    Text(SproutDateStyle.date(day.startMillis()))
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(Str.t("action_close")) { self.day = nil }
                        .font(.callout)
                }
                content(day)
            }
        }
    }
}

struct DetailRow<Trailing: View>: View {
    let time: String
    let text: String
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack {
            Text(time)
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .frame(width: 44, alignment: .leading)
            Text(text)
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.vertical, 2)
    }
}

extension DetailRow where Trailing == EmptyView {
    init(time: String, text: String) {
        self.init(time: time, text: text, trailing: { EmptyView() })
    }
}

struct DetailEmpty: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .padding(.vertical, Spacing.hairline)
    }
}

// MARK: - The sleep breakdown

/// One line of a breakdown, already worded for the screen.
struct ShareRow: Identifiable {
    let label: String
    let count: Int
    let millis: Int64
    /// False for the "not recorded" line, which is drawn as the gap it is.
    let recorded: Bool

    var id: String { label + (recorded ? "" : "\u{0}") }
}

/// A breakdown as bars of one colour, longest first.
///
/// **Deliberately not a palette**: a hue per place would need a colour-vision
/// check of its own (BDR-0008), and would say that the places differ in kind when
/// the only thing being compared is how much of the window each one holds.
struct SleepShares: View {
    let title: String
    let rows: [ShareRow]
    let total: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .padding(.top, Spacing.snug)

            ForEach(rows) { row in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(row.label)
                            .font(.callout)
                            .foregroundStyle(
                                row.recorded ? SproutColor.onSurface : SproutColor.onSurfaceVariant
                            )
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(figure(row))
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurfaceVariant)
                    }

                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(SproutColor.surfaceVariant)
                            Capsule()
                                // The unrecorded line is drawn in the outline
                                // colour rather than the primary one: it is a gap
                                // in the record, not a place the baby slept.
                                .fill(row.recorded ? SproutColor.primary : SproutColor.outline)
                                .frame(width: geometry.size.width * share(row))
                        }
                    }
                    .frame(height: 6)
                }
                .padding(.top, Spacing.hairline)
            }
        }
    }

    private func share(_ row: ShareRow) -> CGFloat {
        guard total > 0 else { return 0 }
        return min(max(CGFloat(row.millis) / CGFloat(total), 0), 1)
    }

    /// A count of zero is a night that began before the window and brought only
    /// its hours in; "0 ×" beside them would read as a contradiction, so it is
    /// simply left off.
    private func figure(_ row: ShareRow) -> String {
        let duration = SproutFormat.duration(millis: row.millis).text
        return row.count == 0 ? duration : Str.t("stats_sleep_share", row.count, duration)
    }
}

// MARK: - Small pieces

struct StatsCard<Content: View>: View {
    let title: String
    let systemImage: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            HStack(spacing: Spacing.tight) {
                Image(systemName: systemImage).foregroundStyle(SproutColor.primary)
                Text(title).font(.headline)
            }
            .padding(.bottom, Spacing.hairline)

            content()
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

struct StatLine: View {
    let label: String
    let value: String
    var emphasis = false

    init(_ label: String, _ value: String, emphasis: Bool = false) {
        self.label = label
        self.value = value
        self.emphasis = emphasis
    }

    var body: some View {
        HStack {
            Text(label)
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            Spacer()
            Text(value)
                .font(emphasis ? .headline : .callout)
        }
        .padding(.vertical, 2)
    }
}

/// A swatch and its name; `hatchWith` draws the overlap of two colours.
struct LegendKey: View {
    let label: String
    let colour: Color
    var hatchWith: Color?

    var body: some View {
        HStack(spacing: Spacing.hairline) {
            Canvas { context, size in
                let rect = CGRect(origin: .zero, size: size)
                context.fill(Path(rect), with: .color(colour))
                if let hatchWith {
                    context.drawHatchLines(in: rect, colour: hatchWith, step: 7, thickness: 3.5)
                }
            }
            .frame(width: 14, height: 10)

            Text(label)
                .font(.caption2)
                .foregroundStyle(SproutColor.onSurfaceVariant)
        }
    }
}

// MARK: - Formatting

/// "3.2 × · 210 ml" — how often, and how much, on an average day.
func countAndAmount(_ count: Double, _ amount: String) -> String {
    Str.t("stats_count_and_amount", decimal(count), amount)
}

/// One decimal place, in the reader's own numbering.
func decimal(_ value: Double) -> String {
    String(format: "%.1f", locale: .current, value)
}

extension Optional where Wrapped == SleepWhere {
    /// What to call a place in the breakdown; a named one is called what it was
    /// called.
    var label: String {
        switch self {
        case .none: return Str.t("stats_sleep_not_recorded")
        case .offered(let place): return place.label
        case .named(let name): return name
        }
    }
}
