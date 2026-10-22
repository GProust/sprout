import SproutData
import SwiftUI
import UIKit

/// *Customise home*: which logs the dashboard's grid shows, and in which order
/// (BDR-25). From `ui/settings/CustomiseHomeScreen.kt`.
///
/// A list rather than tiles to drag about in the grid itself, because a drag is
/// the one gesture VoiceOver cannot make. Each row moves with two buttons that a
/// finger, Switch Control and VoiceOver can all press, and offers the same two
/// moves as accessibility actions. `.onMove` also hands the rows to the list's
/// own reordering, for the parent who would rather drag.
///
/// Nothing here is suggested. The list starts as the grid is, every switch on
/// until the parent turns one off, and nothing on the screen knows which logs
/// are used and which are not.
struct CustomiseHomeScreen: View {
    let model: HomeViewModel

    var body: some View {
        let listed = model.customisableTiles
        List {
            Section {
                ForEach(listed, id: \.self) { id in
                    if let tile = HomeTile.named(id), let index = listed.firstIndex(of: id) {
                        TileRow(
                            tile: tile,
                            shown: !model.homeArrangement.hidden.contains(id),
                            canMoveUp: index > 0,
                            canMoveDown: index < listed.count - 1,
                            onShownChange: { model.setTile(id, shown: $0) },
                            onMove: { move(id, label: tile.label, by: $0) }
                        )
                    }
                }
                .onMove { source, destination in
                    model.moveTiles(fromOffsets: source, toOffset: destination)
                }
            } header: {
                Text(Str.t("customise_home_intro"))
                    .textCase(nil)
            }

            Section {
                Button(Str.t("customise_home_restore")) {
                    model.restoreDefaultTileOrder()
                }
            }
        }
        .navigationTitle(Str.t("screen_customise_home"))
        // A part of Settings, so it opens Settings' sheet (BDR-26).
        .helpButton(.settings)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Moves a log, and says where it landed — "Pumping, position 3 of 8".
    ///
    /// A row that moves keeps VoiceOver's focus but is not read again, so
    /// without the announcement a move is a double tap followed by silence.
    @MainActor
    private func move(_ id: String, label: String, by offset: Int) {
        model.move(tile: id, by: offset)
        let listed = model.customisableTiles
        guard let index = listed.firstIndex(of: id) else { return }
        UIAccessibility.post(
            notification: .announcement,
            argument: Str.t("customise_home_moved", label, index + 1, listed.count)
        )
    }
}

/// One log: its switch, and the two moves.
///
/// The switch carries the name — "Pumping, shown on home" — and is read first;
/// the moves ride on it as actions as well as sitting beside it as buttons.
private struct TileRow: View {
    let tile: HomeTile
    let shown: Bool
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onShownChange: (Bool) -> Void
    let onMove: (Int) -> Void

    var body: some View {
        HStack(spacing: Spacing.snug) {
            Image(systemName: tile.symbol)
                .foregroundStyle(SproutColor.primary)
                .frame(width: 28)
                .accessibilityHidden(true)
            // The switch says the name; read twice, it would be a row of echoes.
            Text(tile.label)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityHidden(true)

            // Borderless, or a tap anywhere on the row would press every button
            // in it at once.
            Button { onMove(-1) } label: {
                Image(systemName: "arrow.up")
            }
            .buttonStyle(.borderless)
            .disabled(!canMoveUp)
            .accessibilityLabel(Str.t("customise_home_move_up"))

            Button { onMove(1) } label: {
                Image(systemName: "arrow.down")
            }
            .buttonStyle(.borderless)
            .disabled(!canMoveDown)
            .accessibilityLabel(Str.t("customise_home_move_down"))

            Toggle(tile.label, isOn: Binding(get: { shown }, set: onShownChange))
                .labelsHidden()
                .accessibilityValue(shown ? Str.t("customise_home_shown") : Str.t("customise_home_hidden"))
                .accessibilityActions {
                    if canMoveUp {
                        Button(Str.t("customise_home_move_up")) { onMove(-1) }
                    }
                    if canMoveDown {
                        Button(Str.t("customise_home_move_down")) { onMove(1) }
                    }
                }
                .accessibilitySortPriority(1)
        }
        .accessibilityElement(children: .contain)
    }
}
