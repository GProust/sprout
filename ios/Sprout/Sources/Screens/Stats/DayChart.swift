import SproutData
import SwiftUI

/// One bar per day, with the window's ends named underneath.
///
/// Deliberately unlabelled between them: at 90 days there is no room for dates,
/// and the shape — a run of empty days, a night that went badly — is what the
/// chart is for. Tapping a day opens what it was made of; dragging sideways walks
/// the window through time, and every chart on the screen moves together.
struct DayChart<Content: View>: View {
    let days: [DayStats]
    let topLabel: String
    @Binding var selectedDay: CalendarDay?
    let onMove: (Int) -> Void
    @ViewBuilder let canvas: (CGSize) -> Content

    private let height: CGFloat = 80

    var body: some View {
        if !days.isEmpty {
            VStack(spacing: Spacing.hairline) {
                HStack {
                    Text(Str.t("stats_per_day"))
                    Spacer()
                    Text(topLabel)
                }
                .font(.caption2)
                .foregroundStyle(SproutColor.onSurfaceVariant)

                GeometryReader { geometry in
                    canvas(geometry.size)
                        .contentShape(Rectangle())
                        .onTapGesture { location in
                            let slot = geometry.size.width / CGFloat(days.count)
                            let index = min(max(Int(location.x / slot), 0), days.count - 1)
                            let picked = days[index].date
                            // Tapping the open day again puts it away.
                            selectedDay = picked == selectedDay ? nil : picked
                        }
                        .gesture(dragGesture(width: geometry.size.width))
                }
                .frame(height: height)

                HStack {
                    Text(SproutDateStyle.date(days[0].date.startMillis()))
                    Spacer()
                    Text(SproutDateStyle.date(days[days.count - 1].date.startMillis()))
                }
                .font(.caption2)
                .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            .padding(.top, Spacing.snug)
        }
    }

    /// A whole chart width is a whole window, so the data tracks the finger.
    ///
    /// `minimumDistance` keeps the vertical scroll: without it the chart claims
    /// every touch that starts on it and the page stops moving.
    private func dragGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onEnded { drag in
                let perDay = width / CGFloat(max(days.count, 1))
                let moved = Int(drag.translation.width / perDay)
                // Pulling right walks backwards, the way a filmstrip would.
                if moved != 0 {
                    selectedDay = nil
                    onMove(moved)
                }
            }
    }
}

// MARK: - The drawing

/// Where a bar goes: the slot it owns, how wide it is drawn, and the inset that
/// centres it.
struct BarGeometry {
    let slot: CGFloat
    let width: CGFloat
    let inset: CGFloat

    init(count: Int, in size: CGSize) {
        slot = size.width / CGFloat(max(count, 1))
        width = max(slot * 0.7, 1)
        inset = (slot - width) / 2
    }
}

extension GraphicsContext {
    /// One bar per day, in one colour.
    func drawDayBars(
        _ days: [DayStats],
        in size: CGSize,
        value: (DayStats) -> Double,
        bar: Color,
        empty: Color
    ) {
        let maximum = max(days.map(value).max() ?? 0, 0.001)
        let geometry = BarGeometry(count: days.count, in: size)

        for (index, day) in days.enumerated() {
            let v = max(value(day), 0)
            // A day with nothing logged keeps a sliver, so the column reads as
            // "a day that happened and was empty" rather than as missing.
            let height = v == 0 ? 2 : (v / maximum) * size.height
            let rect = CGRect(
                x: CGFloat(index) * geometry.slot + geometry.inset,
                y: size.height - height,
                width: geometry.width,
                height: height
            )
            fill(Path(rect), with: .color(v == 0 ? empty : bar))
        }
    }

    /// The nappy bars, split into what each change was made of: urine only, both,
    /// stool only.
    ///
    /// The three add up to the number of changes — stacking `wet` and `dirty`
    /// straight would count every mixed change twice and make the bar disagree
    /// with the figure above it.
    func drawStackedDiapers(_ days: [DayStats], in size: CGSize, wet: Color, dirty: Color, empty: Color) {
        let maximum = max(days.map(\.diaperCount).max() ?? 1, 1)
        let geometry = BarGeometry(count: days.count, in: size)
        let gap: CGFloat = 2

        for (index, day) in days.enumerated() {
            let x = CGFloat(index) * geometry.slot + geometry.inset
            guard day.diaperCount > 0 else {
                let sliver = CGRect(x: x, y: size.height - 2, width: geometry.width, height: 2)
                fill(Path(sliver), with: .color(empty))
                continue
            }

            let unit = size.height / CGFloat(maximum)
            let segments: [(count: Int, colour: Color?)] = [
                (day.wetOnlyCount, wet),
                (day.bothCount, nil),
                (day.dirtyOnlyCount, dirty),
            ].filter { $0.0 > 0 }

            var y = size.height
            for (n, segment) in segments.enumerated() {
                let full = CGFloat(segment.count) * unit
                y -= full
                let height = full - (n < segments.count - 1 ? gap : 0)
                guard height > 0 else { continue }
                let rect = CGRect(x: x, y: y, width: geometry.width, height: height)

                if let colour = segment.colour {
                    fill(Path(rect), with: .color(colour))
                } else {
                    // "Both" is a hatch of the two rather than a third hue — the
                    // segment is literally the overlap of the other two.
                    fill(Path(rect), with: .color(wet))
                    drawHatchLines(in: rect, colour: dirty, step: 8, thickness: 4)
                }
            }
        }
    }

    /// The open day keeps its colours and gains an outline.
    func drawSelection(_ days: [DayStats], in size: CGSize, selected: CalendarDay?, ring: Color) {
        guard let index = days.firstIndex(where: { $0.date == selected }) else { return }
        let geometry = BarGeometry(count: days.count, in: size)
        let rect = CGRect(
            x: CGFloat(index) * geometry.slot + geometry.inset - 3,
            y: 0,
            width: geometry.width + 6,
            height: size.height
        )
        stroke(
            Path(roundedRect: rect, cornerRadius: 4),
            with: .color(ring.opacity(0.8)),
            lineWidth: 2
        )
    }

    /// Diagonal strokes at 45°, for the hatched reference and the mixed nappy.
    func drawHatchLines(in rect: CGRect, colour: Color, step: CGFloat = 9, thickness: CGFloat = 4) {
        var path = Path()
        var x = rect.minX - rect.height
        while x < rect.maxX + rect.height {
            path.move(to: CGPoint(x: x, y: rect.maxY))
            path.addLine(to: CGPoint(x: x + rect.height, y: rect.minY))
            x += step
        }
        // Clipped to the segment, or the strokes run the width of the chart.
        drawLayer { layer in
            layer.clip(to: Path(rect))
            layer.stroke(path, with: .color(colour), lineWidth: thickness)
        }
    }
}

// MARK: - Colours
//
// Not theme roles: these carry meaning of their own, and were picked to survive
// colour-vision deficiency rather than to match the palette (BDR-0008). The band
// pair is diverging — warm below the median, cool above — and the nappy pair
// separates urine from stool. Both were checked with a CVD validator, and the
// dark variants are lightened to hold their contrast on a dark surface.
//
// **Re-run the check before changing one.** The obvious four-hue palettes all
// failed it: every set tried collapsed to a deuteranopic delta-E of 3 to 4,
// which is no distinction at all for roughly one man in twelve.

enum StatsColor {
    static let bandBelow = Color(light: 0xDE8A3F, dark: 0xE9A664)
    static let bandAbove = Color(light: 0x4A93DE, dark: 0x6FA9E6)
    static let dirty = Color(light: 0xB26A2B, dark: 0xCF8949)
}
