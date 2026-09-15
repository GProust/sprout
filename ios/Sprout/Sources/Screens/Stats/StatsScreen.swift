import SproutData
import SwiftUI

/// How wide the window is. Kept short: this is a follow-up, not an archive.
enum StatsPeriod: String, CaseIterable {
    case week, month, quarter

    var days: Int {
        switch self {
        case .week: return 7
        case .month: return 30
        case .quarter: return 90
        }
    }

    var label: String {
        switch self {
        case .week: return Str.t("stats_period_week")
        case .month: return Str.t("stats_period_month")
        case .quarter: return Str.t("stats_period_quarter")
        }
    }
}

/// Which reference the growth card is read against.
///
/// **A view choice, not a fact about the baby**: nothing is stored, nothing is
/// synced, and the app still never asks a baby's sex (BDR-0008). Picking one only
/// narrows what is on screen, and the wording under the chart says so.
enum GrowthReference: String, CaseIterable {
    case both, girls, boys

    var sex: WhoSex? {
        switch self {
        case .both: return nil
        case .girls: return .girls
        case .boys: return .boys
        }
    }

    var label: String {
        switch self {
        case .both: return Str.t("stats_reference_both")
        case .girls: return Str.t("stats_reference_girls")
        case .boys: return Str.t("stats_reference_boys")
        }
    }
}

/// Which number the feeding chart draws.
///
/// They cannot share one chart: a count, a duration, millilitres and grams have
/// nothing to do with each other on a single axis, and putting two of them on two
/// axes would invent a relationship that isn't there. So the card draws one at a
/// time.
enum FeedSeries: String, CaseIterable {
    case count, breast, bottle, solid

    var label: String {
        switch self {
        case .count: return Str.t("stats_feeds_per_day")
        case .breast: return Str.t("stats_breast_per_day")
        case .bottle: return Str.t("stats_bottle_per_day")
        case .solid: return Str.t("stats_solid_per_day")
        }
    }
}

extension GrowthMeasure {
    var label: String {
        switch self {
        case .weight: return Str.t("stats_measure_weight")
        case .length: return Str.t("stats_measure_length")
        case .head: return Str.t("stats_measure_head")
        }
    }

    /// The measure's value for a record, in the unit the WHO tables use; `nil`
    /// when it was not measured.
    func value(of entry: Growth) -> Double? {
        switch self {
        case .weight: return entry.weightGrams.map { Double($0) / 1000 }
        case .length: return entry.heightMm.map { Double($0) / 10 }
        case .head: return entry.headMm.map { Double($0) / 10 }
        }
    }

    func measured(_ value: Double) -> String {
        switch self {
        case .weight: return Str.t("growth_weight_kg", value)
        // Both are a plain "38.4 cm" here; which of the two it is has just been
        // said by the selected chip, so `growth_head_cm` — which names itself —
        // would repeat it.
        case .length, .head: return Str.t("growth_height_cm", value)
        }
    }
}

/// One measurement on the growth chart: how old, and how much.
struct GrowthPoint: Equatable {
    let ageMonths: Double
    let value: Double
}

@Observable
@MainActor
final class StatsViewModel {
    var hasBaby = false
    /// `nil` until a baby is selected; the growth curves are drawn against it.
    var birthDate: Int64?
    var period: StatsPeriod = .week
    /// How many days back the window ends; 0 is "ending today".
    private(set) var offset = 0
    private(set) var maxOffset = 0
    var days: [DayStats] = []
    var averages = StatsAverages()
    var breakdown = SleepBreakdown()
    /// Measurements over the whole history, oldest first.
    var growth: [Growth] = []
    var feedings: [Feeding] = []
    var sleeps: [Sleep] = []
    var diapers: [Diaper] = []
    var now: Int64 = Clock.millis

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    /// Whether anything at all was logged in the window — an empty week says so
    /// once, rather than three times over three empty charts.
    var hasLogs: Bool {
        days.contains { $0.feedCount > 0 || $0.sleepCount > 0 || $0.diaperCount > 0 }
    }

    /// True while the window still ends today, which is when today is left out of
    /// the averages.
    var endsToday: Bool { offset == 0 }

    func setPeriod(_ value: StatsPeriod) {
        period = value
        recompute()
    }

    /// Walks the window through time; `recompute` clamps it to the baby's life.
    ///
    /// The parameter is `count` and not `days`, which would shadow the property
    /// of that name for the length of the function.
    func moveWindow(by count: Int) {
        offset = max(0, offset + count)
        recompute()
    }

    func observeEverything() async {
        let repository = self.repository
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await observe(repository.baby) { [weak self] baby in
                    self?.hasBaby = baby != nil
                    self?.birthDate = baby?.birthDate
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.feedings) { [weak self] in
                    self?.feedings = $0
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.sleeps) { [weak self] in
                    self?.sleeps = $0
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.diapers) { [weak self] in
                    self?.diapers = $0
                    self?.recompute()
                }
            }
            group.addTask {
                await observe(repository.growth) { [weak self] in
                    // Growth is shown over the whole history rather than the
                    // window: a curve is only worth reading over months, and a
                    // fortnight of it would be two dots and no shape.
                    self?.growth = $0.sorted { $0.time < $1.time }
                    self?.recompute()
                }
            }
        }
    }

    private func recompute() {
        now = Clock.millis
        let today = CalendarDay(millis: now)
        let born = birthDate.map { CalendarDay(millis: $0) }

        maxOffset = maxStatsOffset(today: today, birthDay: born)
        offset = min(max(offset, 0), maxOffset)

        let span = statsWindow(today: today, days: period.days, birthDay: born, offset: offset)
        days = dailyStats(
            feedings: feedings,
            sleeps: sleeps,
            diapers: diapers,
            from: span.from,
            to: span.to,
            now: now
        )
        averages = averagesOf(days, today: today)
        breakdown = sleepBreakdown(sleeps, from: span.from, to: span.to, now: now)
    }

    /// The measurements the growth chart can draw, oldest first.
    func growthPoints(for measure: GrowthMeasure) -> [GrowthPoint] {
        guard let birthDate else { return [] }
        return growth.compactMap { entry in
            guard let value = measure.value(of: entry) else { return nil }
            let age = ageInMonths(birthDateMillis: birthDate, at: entry.time)
            return age >= 0 ? GrowthPoint(ageMonths: age, value: value) : nil
        }
    }
}

struct StatsScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: StatsViewModel?
    /// One selected day, shared by the three cards: tapping the 14th in any of
    /// them answers "what happened that day" everywhere at once.
    @State private var selectedDay: CalendarDay?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_stats"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? StatsViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func content(_ model: StatsViewModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.snug) {
                if !model.hasBaby {
                    EmptyHint(Str.t("stats_no_baby"))
                } else {
                    ChoiceChips(
                        options: StatsPeriod.allCases,
                        selection: Binding(
                            get: { model.period },
                            set: { selectedDay = nil; model.setPeriod($0 ?? model.period) }
                        ),
                        label: \.label
                    )

                    WindowBar(model: model) { selectedDay = nil; model.moveWindow(by: $0) }

                    if !model.hasLogs {
                        EmptyHint(Str.t("stats_empty"))
                    } else {
                        FeedingCard(model: model, selectedDay: $selectedDay)
                        SleepCard(model: model, selectedDay: $selectedDay)
                        DiaperCard(model: model, selectedDay: $selectedDay)
                    }

                    GrowthCard(model: model)
                }
            }
            .padding(Spacing.regular)
        }
        .sproutStyle()
    }
}

/// ‹ the window ›, showing its dates once it has walked off today.
private struct WindowBar: View {
    let model: StatsViewModel
    let onMove: (Int) -> Void

    var body: some View {
        HStack {
            Button { onMove(model.period.days) } label: {
                Image(systemName: "chevron.left")
            }
            .disabled(model.offset >= model.maxOffset)
            .accessibilityLabel(Str.t("stats_window_earlier"))

            Text(label)
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)

            Button { onMove(-model.period.days) } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(model.offset <= 0)
            .accessibilityLabel(Str.t("stats_window_later"))
        }
        .buttonStyle(.plain)
        .foregroundStyle(SproutColor.primary)
    }

    private var label: String {
        if !model.endsToday, let first = model.days.first, let last = model.days.last {
            return Str.t(
                "stats_window_range",
                SproutDateStyle.date(first.date.startMillis()),
                SproutDateStyle.date(last.date.startMillis())
            )
        }
        if model.averages.dayCount == 1 && model.days.count == 1 {
            return Str.t("stats_average_today_only")
        }
        return Str.t("stats_average_over", model.averages.dayCount)
    }
}
