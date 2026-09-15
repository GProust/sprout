import SproutData
import SwiftUI

/// The baby's own measurements against the WHO standards.
///
/// The wording under the chart is as much of this card as the chart is. A
/// centile is a comparison, not a verdict: one measurement means little on its
/// own, and what a clinician reads is the shape of a line over months. Nothing
/// here says "low" or "high", and nothing is coloured to mean it.
struct GrowthCard: View {
    let model: StatsViewModel

    @State private var measure: GrowthMeasure = .weight
    @State private var reference: GrowthReference = .both
    /// Which measurement's readout is open; `nil` for none.
    @State private var reading: Int?

    var body: some View {
        StatsCard(title: Str.t("stats_growth_title"), systemImage: "chart.xyaxis.line") {
            ChoiceChips(
                options: GrowthMeasure.allCases,
                selection: Binding(
                    get: { measure },
                    set: { measure = $0 ?? measure; reading = nil }
                ),
                label: \.label
            )
            ChoiceChips(
                options: GrowthReference.allCases,
                selection: Binding(get: { reference }, set: { reference = $0 ?? reference }),
                label: \.label
            )
            .padding(.top, Spacing.hairline)

            let points = model.growthPoints(for: measure)
            if points.isEmpty {
                EmptyHint(Str.t("stats_growth_empty"))
            } else {
                chart(points)
                summary(points)
            }
        }
    }

    @ViewBuilder
    private func chart(_ points: [GrowthPoint]) -> some View {
        let maxAge = min(max(points.map(\.ageMonths).max() ?? 1, 1), whoMaxAgeMonths)
        // A little headroom to the right, so the newest dot isn't on the edge.
        let band = whoBand(measure: measure, fromMonths: 0, toMonths: min(maxAge * 1.15, whoMaxAgeMonths))
        let sexes = reference.sex.map { [$0] } ?? [WhoSex.girls, WhoSex.boys]

        GeometryReader { geometry in
            let plot = GrowthPlot(band: band, points: points, sexes: sexes, size: geometry.size)
            Canvas { context, _ in
                context.drawWhoBand(band, plot: plot, sexes: sexes)
                context.drawOwnCurve(points, plot: plot, colour: SproutColor.primary)
                if let reading, points.indices.contains(reading) {
                    context.drawReadout(
                        points[reading],
                        lines: readoutLines(points[reading]),
                        plot: plot
                    )
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { location in
                let hit = plot.nearestPoint(atX: location.x)
                // Tapping the same measurement again puts the readout away.
                reading = (hit == reading || hit < 0) ? nil : hit
            }
        }
        .frame(height: 200)

        HStack {
            Text(Str.t("stats_age_months", 0))
            Spacer()
            Text(Str.t("stats_age_months", Int((band.last?.ageMonths ?? 0).rounded())))
        }
        .font(.caption2)
        .padding(.top, Spacing.hairline)

        HStack(spacing: 14) {
            LegendKey(label: Str.t("stats_band_above"), colour: StatsColor.bandAbove.opacity(0.55))
            LegendKey(label: Str.t("stats_band_below"), colour: StatsColor.bandBelow.opacity(0.55))
        }
        .padding(.top, Spacing.tight)
    }

    @ViewBuilder
    private func summary(_ points: [GrowthPoint]) -> some View {
        let last = points[points.count - 1]
        let placement = whoPlacement(
            measure: measure, ageMonths: last.ageMonths, value: last.value, only: reference.sex
        )

        Text(
            Str.t(
                "stats_last_measurement",
                measure.measured(last.value),
                Str.t("stats_age_months", Int(last.ageMonths.rounded()))
            )
        )
        .font(.callout.weight(.semibold))
        .padding(.top, Spacing.tight)

        Text(bandSentence(placement))
            .font(.callout)
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .padding(.top, 2)

        Text(disclaimer)
            .font(.caption)
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .padding(.top, Spacing.tight)
    }

    private func bandSentence(_ placement: WhoPlacement?) -> String {
        guard let placement else { return Str.t("stats_who_past_range") }
        let range = percentileRange(placement)
        return placement.insideBand
            ? Str.t("stats_who_inside", range)
            : Str.t("stats_who_outside", range)
    }

    private var disclaimer: String {
        switch reference.sex {
        case nil:
            return Str.t("stats_who_disclaimer_both")
        // Not lowercased from the chip label: German capitalises its nouns, so
        // folding the case there would be wrong in the one language that cares.
        case .girls:
            return Str.t("stats_who_disclaimer_one", Str.t("stats_reference_girls_in_sentence"))
        case .boys:
            return Str.t("stats_who_disclaimer_one", Str.t("stats_reference_boys_in_sentence"))
        }
    }

    /// "5.95 kg · 2.8 months" then the percentile, read against what is on screen.
    private func readoutLines(_ point: GrowthPoint) -> [String] {
        let age = point.ageMonths < 2
            ? Str.t("stats_age_days", Int((point.ageMonths * 30.4375).rounded()))
            : Str.t("stats_age_months", Int(point.ageMonths.rounded()))
        let first = Str.t("stats_readout_measure", measure.measured(point.value), age)

        guard let placement = whoPlacement(
            measure: measure, ageMonths: point.ageMonths, value: point.value, only: reference.sex
        ) else {
            return [first, Str.t("stats_readout_past_range")]
        }
        return [first, percentileRange(placement)]
    }

    /// "P30–P48" — a span rather than one number when both references are on
    /// screen, because they disagree and Sprout does not know which one applies.
    /// The notation is the one printed on growth charts and in health records, in
    /// every language we ship.
    private func percentileRange(_ placement: WhoPlacement) -> String {
        let low = min(max(Int(placement.lowPercentile.rounded()), 1), 99)
        let high = min(max(Int(placement.highPercentile.rounded()), 1), 99)
        return low == high
            ? Str.t("stats_percentile_one", low)
            : Str.t("stats_percentile_range", low, high)
    }
}

/// Where the growth chart puts things, given a canvas size.
///
/// Pulled out of the drawing so the tap handler can ask the same question the
/// pixels answer — which measurement is under this finger — without the two
/// drifting apart.
struct GrowthPlot {
    let size: CGSize

    private let maxAge: Double
    private let minimum: Double
    private let span: Double
    private let padY: CGFloat
    private let usable: CGFloat
    private let xs: [CGFloat]

    init(band: [WhoBandPoint], points: [GrowthPoint], sexes: [WhoSex], size: CGSize) {
        self.size = size
        maxAge = max(max(band.last?.ageMonths ?? 0, points.map(\.ageMonths).max() ?? 0), 1)

        let values = band.flatMap { p in sexes.flatMap { [p.of($0).p3, p.of($0).p97] } }
            + points.map(\.value)
        minimum = values.min() ?? 0
        span = max((values.max() ?? 1) - minimum, 0.001)
        padY = size.height * 0.06
        usable = size.height - padY * 2

        // The measurements' horizontal positions, kept so the tap handler and
        // the drawing cannot disagree about which dot is where. Computed from
        // the local `maxAge` rather than through `x(_:)`, since `self` is not
        // whole yet.
        let widest = max(max(band.last?.ageMonths ?? 0, points.map(\.ageMonths).max() ?? 0), 1)
        xs = points.map { CGFloat($0.ageMonths / widest) * size.width }
    }

    func x(_ ageMonths: Double) -> CGFloat { CGFloat(ageMonths / maxAge) * size.width }

    func y(_ value: Double) -> CGFloat {
        padY + (1 - CGFloat((value - minimum) / span)) * usable
    }

    /// The measurement nearest a horizontal position, or -1 when there are none.
    func nearestPoint(atX: CGFloat) -> Int {
        guard !xs.isEmpty else { return -1 }
        var best = 0
        for i in xs.indices where abs(xs[i] - atX) < abs(xs[best] - atX) { best = i }
        return best
    }
}

extension GraphicsContext {

    /// The reference behind the baby's own line.
    ///
    /// Two encodings, kept apart so neither has to carry the other's meaning:
    ///
    /// - **Colour is the side of the median.** Warm below it, cool above it — a
    ///   diverging pair, which is what a diverging pair is for, and validated to
    ///   stay distinguishable under colour-vision deficiency.
    /// - **Texture is which reference.** With both shown, the girls' band is
    ///   plain and the boys' is hatched, each keeping its own median line. Two
    ///   more hues would have been the obvious move and the wrong one: every
    ///   four-hue set tried collapsed to a deuteranopic delta-E of 3 to 4, which
    ///   is no distinction at all for roughly one man in twelve.
    func drawWhoBand(_ band: [WhoBandPoint], plot: GrowthPlot, sexes: [WhoSex]) {
        guard band.count >= 2 else { return }

        func area(
            _ lower: @escaping (WhoPercentiles) -> Double,
            _ upper: @escaping (WhoPercentiles) -> Double,
            _ sex: WhoSex
        ) -> Path {
            var path = Path()
            for (i, p) in band.enumerated() {
                let point = CGPoint(x: plot.x(p.ageMonths), y: plot.y(upper(p.of(sex))))
                if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            for p in band.reversed() {
                path.addLine(to: CGPoint(x: plot.x(p.ageMonths), y: plot.y(lower(p.of(sex)))))
            }
            path.closeSubpath()
            return path
        }

        for sex in sexes {
            let hatched = sexes.count > 1 && sex == .boys
            let areas: [(Path, Color)] = [
                (area({ $0.p3 }, { $0.p50 }, sex), StatsColor.bandBelow),
                (area({ $0.p50 }, { $0.p97 }, sex), StatsColor.bandAbove),
                // The 15th–85th again on top, so the middle of the reference
                // reads as the middle rather than as more of the same.
                (area({ $0.p15 }, { $0.p50 }, sex), StatsColor.bandBelow),
                (area({ $0.p50 }, { $0.p85 }, sex), StatsColor.bandAbove),
            ]

            for (i, entry) in areas.enumerated() {
                let alpha = i < 2 ? 0.15 : 0.18
                if hatched {
                    drawLayer { layer in
                        layer.clip(to: entry.0)
                        layer.drawHatchLines(
                            in: CGRect(origin: .zero, size: plot.size),
                            colour: entry.1.opacity(alpha * 2.2)
                        )
                    }
                } else {
                    fill(entry.0, with: .color(entry.1.opacity(alpha)))
                }
            }

            var median = Path()
            for (i, p) in band.enumerated() {
                let point = CGPoint(x: plot.x(p.ageMonths), y: plot.y(p.of(sex).p50))
                if i == 0 { median.move(to: point) } else { median.addLine(to: point) }
            }
            stroke(
                median,
                with: .color(SproutColor.onSurfaceVariant.opacity(0.6)),
                style: StrokeStyle(lineWidth: 2, dash: hatched ? [9, 4] : [4, 4])
            )
        }
    }

    func drawOwnCurve(_ points: [GrowthPoint], plot: GrowthPlot, colour: Color) {
        let own = points.map { CGPoint(x: plot.x($0.ageMonths), y: plot.y($0.value)) }

        if own.count >= 2 {
            var path = Path()
            for (i, p) in own.enumerated() {
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            stroke(path, with: .color(colour), lineWidth: 3)
        }
        for p in own {
            fill(Path(ellipseIn: CGRect(x: p.x - 4.5, y: p.y - 4.5, width: 9, height: 9)),
                 with: .color(colour))
        }
    }

    /// The crosshair and the card: what this measurement was, and where it lands.
    func drawReadout(_ point: GrowthPoint, lines: [String], plot: GrowthPlot) {
        let px = plot.x(point.ageMonths)
        let py = plot.y(point.value)
        let ink = SproutColor.onSurface

        var crosshair = Path()
        crosshair.move(to: CGPoint(x: px, y: 0))
        crosshair.addLine(to: CGPoint(x: px, y: plot.size.height))
        stroke(
            crosshair,
            with: .color(ink.opacity(0.35)),
            style: StrokeStyle(lineWidth: 1, dash: [6, 6])
        )
        stroke(
            Path(ellipseIn: CGRect(x: px - 7, y: py - 7, width: 14, height: 14)),
            with: .color(ink.opacity(0.55)),
            lineWidth: 1.5
        )

        let laid = lines.map { resolve(Text($0).font(.caption2).foregroundStyle(ink)) }
        let sizes = laid.map { $0.measure(in: CGSize(width: 200, height: 100)) }
        let cardWidth = (sizes.map(\.width).max() ?? 0) + 12
        let cardHeight = sizes.reduce(0) { $0 + $1.height } + 10

        // Flip the card to the other side rather than let it run off the chart.
        let left = min(max(px + 8, 2), max(plot.size.width - cardWidth - 2, 2))
        let top = min(max(py - cardHeight - 8, 2), max(plot.size.height - cardHeight - 2, 2))
        let card = CGRect(x: left, y: top, width: cardWidth, height: cardHeight)

        fill(Path(roundedRect: card, cornerRadius: 5), with: .color(SproutColor.surface))
        stroke(
            Path(roundedRect: card, cornerRadius: 5),
            with: .color(SproutColor.outlineVariant),
            lineWidth: 1
        )

        var y = top + 5
        for (text, size) in zip(laid, sizes) {
            draw(text, at: CGPoint(x: left + 6, y: y), anchor: .topLeading)
            y += size.height
        }
    }
}
