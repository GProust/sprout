import SproutData
import SwiftUI

/// Weight, length and head circumference, from `ui/growth/GrowthScreen.kt`.
@Observable
@MainActor
final class GrowthViewModel {
    var measurements: [Growth] = []

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeGrowth() async {
        await observe(repository.growth) { [weak self] in self?.measurements = $0 }
    }

    func add(_ growth: Growth) { try? repository.addGrowth(growth) }
    func delete(_ growth: Growth) { try? repository.deleteGrowth(growth) }

    /// Weights only, oldest first — what the trend is drawn from.
    var weighed: [Growth] {
        measurements.filter { $0.weightGrams != nil }.sorted { $0.time < $1.time }
    }
}

struct GrowthScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: GrowthViewModel?
    @State private var adding = false
    @State private var pendingDelete: Growth?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_growth"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? GrowthViewModel(repository: sprout.repository)
            self.model = model
            await model.observeGrowth()
        }
    }

    @ViewBuilder
    private func content(_ model: GrowthViewModel) -> some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Spacing.snug) {
                    // One weight is a dot, not a trend. Two is the fewest that
                    // can go up or down.
                    if model.weighed.count >= 2 {
                        WeightTrendCard(points: model.weighed)
                    }

                    SectionLabel(Str.t("history"))

                    if model.measurements.isEmpty {
                        EmptyHint(Str.t("growth_empty"))
                    }
                    ForEach(model.measurements) { entry in
                        EntryCard(
                            title: entry.title,
                            subtitle: entry.notes ?? "",
                            meta: SproutDateStyle.date(entry.time),
                            systemImage: "ruler",
                            onDelete: { pendingDelete = entry }
                        )
                    }
                }
                .padding(Spacing.regular)
                .padding(.bottom, 80)
            }

            AddEntryButton(accessibilityLabel: Str.t("growth_log_title")) { adding = true }
                .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(isPresented: $adding) {
            GrowthForm { model.add($0); adding = false }
        }
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            if let entry = pendingDelete { model.delete(entry) }
            pendingDelete = nil
        }
    }
}

extension Growth {
    /// "4.20 kg · 54.0 cm · head 37.5 cm" — whichever were measured.
    ///
    /// Units are fixed rather than localised, which is Android's choice too: a
    /// paediatrician's card is in kilos and centimetres everywhere Sprout is
    /// translated into, and a number a parent reads out at an appointment should
    /// not depend on their phone's region.
    var title: String {
        var parts: [String] = []
        if let weightGrams { parts.append(Str.t("growth_weight_kg", Double(weightGrams) / 1000)) }
        if let heightMm { parts.append(Str.t("growth_height_cm", Double(heightMm) / 10)) }
        if let headMm { parts.append(Str.t("growth_head_cm", Double(headMm) / 10)) }
        return parts.isEmpty
            ? Str.t("growth_measurement")
            : parts.joined(separator: Str.t("feeding_detail_separator"))
    }
}

// MARK: - The trend

private struct WeightTrendCard: View {
    let points: [Growth]

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            Text(Str.t("growth_weight_trend"))
                .font(.headline)
                .foregroundStyle(SproutColor.onSurface)

            WeightChart(grams: points.compactMap(\.weightGrams))
                .frame(height: 160)
                .accessibilityLabel(Str.t("growth_weight_trend"))
                // The chart is a shape; VoiceOver gets the numbers instead.
                .accessibilityValue(
                    points.compactMap(\.weightGrams)
                        .map { Str.t("growth_weight_kg", Double($0) / 1000) }
                        .joined(separator: ", ")
                )

            HStack {
                if let first = points.first {
                    Text(SproutDateStyle.date(first.time))
                }
                Spacer()
                if let last = points.last {
                    Text(SproutDateStyle.date(last.time))
                }
            }
            .font(.caption2)
            .foregroundStyle(SproutColor.onSurfaceVariant)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

/// The weight line, drawn by hand as `ui/growth/GrowthScreen.kt` draws it.
///
/// **Points are spaced evenly by index, not by date** — two measurements a month
/// apart sit the same distance apart as two taken on consecutive days. That is
/// what Android does, and this matches it deliberately rather than quietly
/// improving on it: a chart that read differently on the two phones would be a
/// worse outcome than one that reads the same and is wrong in the same way. If
/// it should become time-proportional, it should change on both sides at once.
private struct WeightChart: View {
    let grams: [Int]

    var body: some View {
        GeometryReader { geometry in
            let coordinates = points(in: geometry.size)
            ZStack {
                Path { path in
                    guard let first = coordinates.first else { return }
                    path.move(to: first)
                    for point in coordinates.dropFirst() { path.addLine(to: point) }
                }
                .stroke(SproutColor.primary, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))

                ForEach(Array(coordinates.enumerated()), id: \.offset) { _, point in
                    Circle()
                        .fill(SproutColor.primary)
                        .frame(width: 7, height: 7)
                        .position(point)
                }
            }
        }
    }

    private func points(in size: CGSize) -> [CGPoint] {
        guard let lowest = grams.min(), let highest = grams.max() else { return [] }
        // A flat line still has to be drawn somewhere, so a zero span becomes 1
        // and every point lands mid-height.
        let span = max(highest - lowest, 1)
        let padding = size.height * 0.1
        let usable = size.height - padding * 2

        return grams.enumerated().map { index, value in
            let x = grams.count == 1
                ? size.width / 2
                : size.width * CGFloat(index) / CGFloat(grams.count - 1)
            let normalised = CGFloat(value - lowest) / CGFloat(span)
            return CGPoint(x: x, y: padding + (1 - normalised) * usable)
        }
    }
}

// MARK: - The form

private struct GrowthForm: View {
    let onAdd: (Growth) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var weight = ""
    @State private var height = ""
    @State private var head = ""
    @State private var time = Clock.millis
    @State private var notes = ""

    /// Grams and millimetres, so nothing is lost to rounding on the way in.
    /// A parent types 4.2 kg; the scale said 4200 g.
    private var weightGrams: Int? { Int(weight).map { $0 } }
    private var heightMm: Int? { Int(height).map { $0 } }
    private var headMm: Int? { Int(head).map { $0 } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NumberField(label: Str.t("field_weight"), text: $weight, suffix: "g")
                    NumberField(label: Str.t("field_height"), text: $height, suffix: "mm")
                    NumberField(label: Str.t("field_head"), text: $head, suffix: "mm")
                }
                Section {
                    DateTimeField(label: Str.t("field_date"), millis: $time)
                    NotesField(text: $notes)
                }
            }
            .navigationTitle(Str.t("growth_log_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(Str.t("growth_add")) {
                        onAdd(
                            Growth(
                                time: time,
                                weightGrams: weightGrams,
                                heightMm: heightMm,
                                headMm: headMm,
                                notes: notes.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                            )
                        )
                    }
                    // A measurement with nothing measured is not a measurement.
                    .disabled(weightGrams == nil && heightMm == nil && headMm == nil)
                }
            }
        }
    }
}
