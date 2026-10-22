import SproutData
import SwiftUI
import UIKit

/// Where each step's area is on screen, as the areas themselves report it. From
/// `TourTargets` in `ui/tour/Tour.kt`.
///
/// Measured rather than computed: the dashboard is a different shape with one
/// baby or two, with a feed running or a medicine waiting, and in seven
/// languages at any text size. A step whose area has not reported shows its
/// card with no cut-out, and the card still says in words where the area is.
///
/// Frames are in the window's coordinates (`.global`), which mean the same on
/// both sides of the tab view: the overlay sits over all of it, and the areas
/// are inside one of its tabs.
@Observable
final class TourTargets {
    private(set) var frames: [TourStep: CGRect] = [:]

    func report(_ step: TourStep, _ frame: CGRect) {
        if frames[step] != frame { frames[step] = frame }
    }

    func forget(_ step: TourStep) {
        frames[step] = nil
    }
}

private struct TourTargetsKey: EnvironmentKey {
    static let defaultValue: TourTargets? = nil
}

extension EnvironmentValues {
    /// Set by the shell on the dashboard's own stack and nowhere else, which is
    /// what keeps the baby's tab — the same pane, with the same modifiers — and
    /// every pushed screen from reporting anything.
    var tourTargets: TourTargets? {
        get { self[TourTargetsKey.self] }
        set { self[TourTargetsKey.self] = newValue }
    }
}

extension View {
    /// Marks this view as the area `step` points at, or does nothing for nil.
    /// Nothing at all happens outside the dashboard: see
    /// ``EnvironmentValues/tourTargets``.
    func tourTarget(_ step: TourStep?) -> some View {
        modifier(TourTargetModifier(step: step))
    }
}

private struct TourTargetModifier: ViewModifier {
    let step: TourStep?
    @Environment(\.tourTargets) private var targets

    @ViewBuilder
    func body(content: Content) -> some View {
        if let step, let targets {
            content.background {
                GeometryReader { proxy in
                    let frame = proxy.frame(in: .global)
                    Color.clear
                        .onAppear { targets.report(step, frame) }
                        .onChange(of: frame) { _, moved in targets.report(step, moved) }
                        .onDisappear { targets.forget(step) }
                }
            }
        } else {
            content
        }
    }
}

/// One step of the guided tour (BDR-27), from `TourOverlay` in
/// `ui/tour/Tour.kt`: the screen dimmed, a cut-out around the area the step is
/// about, and a card saying what it is.
///
/// Drawn by hand rather than with TipKit, which cannot put tips in a sequence
/// before iOS 18 (`TipGroup`), and the deployment target is 17. Hand-written on
/// both sides also means the same steps, words and behaviour on both.
///
/// - **The cut-out stays live.** The scrim's shape leaves it out, so a tap there
///   reaches the very thing the card describes — and doing so leaves the
///   dashboard or starts a feed, either of which ends the tour.
/// - **Everywhere else skips.** A tap outside the card is *Skip tour*, and so
///   is VoiceOver's escape gesture, which is the nearest this platform has to
///   Android's Back.
/// - **The cut-out is visual only.** Each card says in words where its area
///   is; the shell hides what the scrim dims from VoiceOver while a step is up,
///   and focus moves to the card, where *Skip tour* is the first thing reached.
/// - **No movement but a short fade**, which the shell drops when Reduce
///   Motion is on.
struct TourOverlay: View {
    let step: TourStep
    let position: Int
    let count: Int
    let isLast: Bool
    /// Where the step's area is, in the window's coordinates, or nil when it is
    /// not on screen.
    let target: CGRect?
    let onNext: () -> Void
    let onSkip: () -> Void

    /// Less room than this on both sides of the cut-out, and the card sits at
    /// the bottom of the screen instead, over whatever is there.
    private static let minimumRoom: CGFloat = 180
    /// Room around a target, so the ring does not sit on the thing it circles.
    private static let margin: CGFloat = 6

    var body: some View {
        ZStack {
            // Over everything, the bars included.
            GeometryReader { proxy in
                scrim(hole: hole(in: proxy))
            }
            .ignoresSafeArea()

            // The card stays inside the safe area.
            GeometryReader { proxy in
                placed(hole: hole(in: proxy), height: proxy.size.height)
            }
        }
        // A new screen as far as VoiceOver is concerned, as it appears and as
        // each step replaces the last: focus goes to the card.
        .onAppear { UIAccessibility.post(notification: .screenChanged, argument: nil) }
        .onChange(of: step) { UIAccessibility.post(notification: .screenChanged, argument: nil) }
    }

    /// The cut-out in a reader's own coordinates, clipped to what it covers.
    private func hole(in proxy: GeometryProxy) -> CGRect? {
        guard let target else { return nil }
        let origin = proxy.frame(in: .global).origin
        var rect = target.offsetBy(dx: -origin.x, dy: -origin.y)
        // The two bars are UIKit's and report no frame of their own. What the
        // dashboard reports for them is where its safe area ends, which is
        // where each bar begins: the tab bar is everything below that edge,
        // and the navigation bar, gear and all, everything above it.
        switch step {
        case .bottomBar:
            rect = CGRect(x: 0, y: rect.minY, width: proxy.size.width, height: max(0, proxy.size.height - rect.minY))
        case .settings:
            rect = CGRect(x: 0, y: 0, width: proxy.size.width, height: max(0, rect.maxY))
        case .logTiles, .breastfeed:
            break
        }
        let clipped = rect
            .insetBy(dx: -Self.margin, dy: -Self.margin)
            .intersection(CGRect(origin: .zero, size: proxy.size))
        return clipped.isNull || clipped.isEmpty ? nil : clipped
    }

    private func scrim(hole: CGRect?) -> some View {
        ZStack(alignment: .topLeading) {
            TourScrim(hole: hole)
                .fill(Color.black.opacity(0.6), style: FillStyle(eoFill: true))
                // The same shape for touches as for ink, so the cut-out lets
                // them through to whatever is under it.
                .contentShape(TourScrim(hole: hole), eoFill: true)
                .onTapGesture(perform: onSkip)
            if let hole {
                RoundedRectangle(cornerRadius: Radius.control)
                    .stroke(SproutColor.primaryContainer, lineWidth: 2)
                    .frame(width: hole.width, height: hole.height)
                    .offset(x: hole.minX, y: hole.minY)
                    .allowsHitTesting(false)
            }
        }
        .accessibilityHidden(true)
    }

    /// The card on whichever side of the cut-out has more room.
    ///
    /// Clear spacers rather than offsets: a clear view takes no touches, so the
    /// space around the card still reaches the scrim beneath it, and the
    /// cut-out what is beneath that.
    @ViewBuilder
    private func placed(hole: CGRect?, height: CGFloat) -> some View {
        let roomAbove = hole?.minY ?? 0
        let roomBelow = hole.map { height - $0.maxY } ?? 0
        let gap = Spacing.snug
        VStack(spacing: 0) {
            if let hole, max(roomAbove, roomBelow) >= Self.minimumRoom {
                if roomAbove > roomBelow {
                    Spacer(minLength: 0)
                    card
                    Color.clear.frame(height: max(0, height - hole.minY + gap - Spacing.regular))
                } else {
                    Color.clear.frame(height: max(0, hole.maxY + gap - Spacing.regular))
                    card
                    Spacer(minLength: 0)
                }
            } else if hole != nil {
                Spacer(minLength: 0)
                card
            } else {
                Spacer(minLength: 0)
                card
                Spacer(minLength: 0)
            }
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var card: some View {
        TourCard(
            step: step,
            position: position,
            count: count,
            isLast: isLast,
            onNext: onNext,
            onSkip: onSkip
        )
        .frame(maxWidth: 480)
    }
}

/// The screen with a rounded hole in it, filled even-odd.
private struct TourScrim: Shape {
    let hole: CGRect?

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        if let hole {
            path.addRoundedRect(in: hole, cornerSize: CGSize(width: Radius.control, height: Radius.control))
        }
        return path
    }
}

private struct TourCard: View {
    let step: TourStep
    let position: Int
    let count: Int
    let isLast: Bool
    let onNext: () -> Void
    let onSkip: () -> Void

    var body: some View {
        // Scrolls only when the words do not fit, at the largest text sizes.
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content }
        }
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape, onSkip)
    }

    private var content: some View {
        let words = tourWords(step)
        return VStack(alignment: .leading, spacing: Spacing.tight) {
            HStack {
                Text(Str.t("tour_step_count", position, count))
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
                Spacer()
                // Where a skip is expected to be, at the end of the row — and
                // ahead of everything else for VoiceOver, which reaches it
                // before the step's own words.
                Button(Str.t("tour_skip"), action: onSkip)
                    .font(.callout.weight(.medium))
                    .accessibilitySortPriority(1)
                    // By identifier, not by label: the first-run capture skips
                    // the tour in seven languages.
                    .accessibilityIdentifier("tour-skip")
            }
            Text(words.title)
                .font(.headline)
                .foregroundStyle(SproutColor.onSurface)
                .accessibilityAddTraits(.isHeader)
            Text(words.body)
                .font(.callout)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(isLast ? Str.t("tour_done") : Str.t("action_next"), action: onNext)
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("tour-next")
            }
            .padding(.top, Spacing.hairline)
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// What one step says: the same keys as Android's, generated from them. The
/// keys are literal so that `check_string_keys.py` can see each one.
private struct TourWords {
    let title: String
    let body: String
}

private func tourWords(_ step: TourStep) -> TourWords {
    switch step {
    case .logTiles:
        return TourWords(title: Str.t("tour_step_1_title"), body: Str.t("tour_step_1_body"))
    case .breastfeed:
        return TourWords(title: Str.t("tour_step_2_title"), body: Str.t("tour_step_2_body"))
    case .bottomBar:
        return TourWords(title: Str.t("tour_step_3_title"), body: Str.t("tour_step_3_body"))
    case .settings:
        return TourWords(title: Str.t("tour_step_4_title"), body: Str.t("tour_step_4_body"))
    }
}
