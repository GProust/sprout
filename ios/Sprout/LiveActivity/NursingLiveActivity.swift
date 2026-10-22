import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The extension's one widget: the running breastfeed (BDR-24, ADR-0020).
///
/// It only draws. Every value on it was worked out by the app, and every button
/// is a `NursingControlIntent` the system runs in the app's own process — so
/// nothing here reads the database, the session store or the network.
@main
struct SproutLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        NursingLiveActivity()
    }
}

struct NursingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NursingActivityAttributes.self) { context in
            NursingLockScreen(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.state.title, systemImage: symbol(context.state))
                        .font(.callout.weight(.medium))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    NursingClock(state: context.state)
                        .font(.title3.weight(.semibold))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 6) {
                        if let detail = context.state.detail {
                            Text(detail).font(.caption)
                        }
                        NursingButtons(context: context)
                    }
                }
            } compactLeading: {
                Image(systemName: symbol(context.state))
            } compactTrailing: {
                NursingClock(state: context.state)
                    .frame(maxWidth: 56)
            } minimal: {
                Image(systemName: symbol(context.state))
            }
        }
    }
}

/// The drop while feeding, the pause sign on a break — the same symbols the
/// app's own live row uses, so the state is never carried by colour alone.
private func symbol(_ state: NursingActivityAttributes.ContentState) -> String {
    state.isPaused ? "pause.circle.fill" : "drop.fill"
}

/// On the lock screen: what is running, the time at the breast, and the buttons.
private struct NursingLockScreen: View {
    let context: ActivityViewContext<NursingActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: symbol(context.state))
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.state.title)
                        .font(.headline)
                    if let detail = context.state.detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                NursingClock(state: context.state)
                    .font(.title2.weight(.semibold))
                    .frame(maxWidth: 110, alignment: .trailing)
            }
            NursingButtons(context: context)
        }
        .padding()
    }
}

/// Time at the breast, counted by the system: it ticks while nursing and stands
/// still on a break, because a break is not time at the breast (BDR-17).
private struct NursingClock: View {
    let state: NursingActivityAttributes.ContentState

    var body: some View {
        Text(
            timerInterval: state.clockStart...Date.distantFuture,
            pauseTime: state.clockStoppedAt,
            countsDown: false,
            showsHours: true
        )
        .monospacedDigit()
        .multilineTextAlignment(.trailing)
    }
}

/// The feed's buttons, in the order the app gave them — three at most.
private struct NursingButtons: View {
    let context: ActivityViewContext<NursingActivityAttributes>

    var body: some View {
        HStack(spacing: 8) {
            ForEach(context.state.controls, id: \.self) { control in
                Button(intent: NursingControlIntent(babyUid: context.attributes.babyUid, control: control)) {
                    Text(context.attributes.labels[control] ?? control)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
    }
}

/// What a button means on this side: nothing. A `LiveActivityIntent` runs in
/// the app's process, where `NursingActivityControls` does the work; this one
/// exists so the shared intent compiles here, and is never called.
enum NursingActivityControls {
    @MainActor
    static func perform(_ control: String, babyUid: String?) async {}
}
