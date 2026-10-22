import Foundation

/// Every log tile on the dashboard, by the stable id it is stored under, in the
/// order a phone nobody has arranged draws them (BDR-25). From `HOME_TILE_IDS`
/// in `data/HomeLayout.kt`.
///
/// The ids are the names the tiles already carried in their accessibility
/// identifiers (`log-tile-<name>`), and one list for both apps. A parent's
/// arrangement is stored by these, so an id is never renamed or reused; a log
/// added later appends its own. The order is the one the grid had before it
/// could be arranged at all, so a parent who never opens *Customise home* sees
/// no change.
public let homeTileIds: [String] = [
    "feeding", "pumping", "sleep", "diaper", "growth",
    "treatments", "medicines", "foods", wellbeingTileId,
]

/// The one tile with a master switch of its own (BDR-0006).
public let wellbeingTileId = "wellbeing"

/// What this phone's parent has made of the grid: an order, and the tiles put
/// away behind *More*. Empty is "nothing stored", which is the default grid.
public struct HomeArrangement: Equatable, Sendable {
    public var order: [String]
    public var hidden: Set<String>

    public init(order: [String] = [], hidden: Set<String> = []) {
        self.order = order
        self.hidden = hidden
    }
}

/// What the grid draws: the tiles shown, in order, and the ones waiting behind
/// *More*.
public struct HomeTiles: Equatable, Sendable {
    public let shown: [String]
    public let hidden: [String]

    public init(shown: [String], hidden: [String]) {
        self.shown = shown
        self.hidden = hidden
    }
}

/// Every tile in `defaults`, once each, in the order `stored` puts them.
///
/// Two things a stored order can be out of step with, because it was written by
/// another version of the app:
/// - **A tile it does not name** — a log added after the parent last arranged
///   the grid — goes in at its place in the default order, counted from the
///   start. It is new to them, and it is the app's to place until they move it.
/// - **An id the app no longer knows** is dropped. A stored list is never a
///   reason for a tile to exist.
public func tileOrder(defaults: [String], stored: [String]) -> [String] {
    let known = Set(defaults)
    var order: [String] = []
    for id in stored where known.contains(id) && !order.contains(id) {
        order.append(id)
    }
    for (index, id) in defaults.enumerated() where !order.contains(id) {
        order.insert(id, at: min(index, order.count))
    }
    return order
}

/// The tiles *Customise home* lists, in order: all of them, except wellbeing
/// for a parent who has turned their own tracking off (BDR-0006) — the tile is
/// not in the grid then, and an editor offering to show it would be offering
/// nothing.
public func listedTiles(defaults: [String], storedOrder: [String], tracksWellbeing: Bool) -> [String] {
    tileOrder(defaults: defaults, stored: storedOrder)
        .filter { tracksWellbeing || $0 != wellbeingTileId }
}

/// The grid as this parent arranged it.
///
/// Hiding is presentation only: a hidden tile is drawn behind *More* rather
/// than not at all, so its screen stays one tap away. A tile the parent never
/// touched is shown — nothing is hidden that they did not hide.
public func arrangeTiles(
    defaults: [String],
    storedOrder: [String],
    hidden: Set<String>,
    tracksWellbeing: Bool
) -> HomeTiles {
    let listed = listedTiles(defaults: defaults, storedOrder: storedOrder, tracksWellbeing: tracksWellbeing)
    return HomeTiles(
        shown: listed.filter { !hidden.contains($0) },
        hidden: listed.filter { hidden.contains($0) }
    )
}

/// The order to store once the editor's list reads `listed` — after a drag, or
/// a move.
///
/// Only the listed tiles move: they are put back into the places they held
/// between them, in their new order, and anything the editor was not showing —
/// wellbeing while it is switched off — keeps its own place. That is what lets
/// the tile come back where the parent had put it when tracking is turned on
/// again. A `listed` that names a tile twice, or one the app does not know, is
/// not a rearrangement of anything and changes nothing.
public func reorderTiles(defaults: [String], storedOrder: [String], listed: [String]) -> [String] {
    let order = tileOrder(defaults: defaults, stored: storedOrder)
    let slots = Set(listed)
    guard listed.count == slots.count, slots.isSubset(of: Set(order)) else { return order }
    var incoming = listed.makeIterator()
    return order.map { id in
        guard slots.contains(id), let next = incoming.next() else { return id }
        return next
    }
}

/// The order to store after `id` is moved `offset` places in the editor's
/// list: -1 is *Move up*, +1 *Move down*. A move past either end stops there.
public func moveTile(
    defaults: [String],
    storedOrder: [String],
    id: String,
    by offset: Int,
    tracksWellbeing: Bool
) -> [String] {
    var listed = listedTiles(defaults: defaults, storedOrder: storedOrder, tracksWellbeing: tracksWellbeing)
    guard let from = listed.firstIndex(of: id) else {
        return tileOrder(defaults: defaults, stored: storedOrder)
    }
    let to = min(max(from + offset, 0), listed.count - 1)
    let moving = listed.remove(at: from)
    listed.insert(moving, at: to)
    return reorderTiles(defaults: defaults, storedOrder: storedOrder, listed: listed)
}

/// How this phone's parent has arranged the grid, from `HomeLayoutSettings.kt`.
///
/// In the ordinary settings (``UserDefaultsStore``), not the Keychain: it is
/// about a person's habits rather than this handset, so a restored phone keeps
/// it (ADR-0011, ADR-0018). It is not synced, because the other parent arranges
/// their own phone; and it is one arrangement for every baby, because with twins
/// both panes want the same order.
///
/// Nothing stored is the default grid, and *Restore the default order* stores
/// nothing again rather than writing today's list down — so a tile added in a
/// later release takes its default place on a phone that never moved anything.
public struct HomeLayoutSettings {
    private static let orderKey = "home_tile_order"
    private static let hiddenKey = "home_tiles_hidden"
    /// Tile ids are lower-case words, so a comma never occurs in one.
    private static let separator: Character = ","

    private let store: any DeviceLocalStore

    public init(store: any DeviceLocalStore) {
        self.store = store
    }

    /// The arrangement as stored.
    public var arrangement: HomeArrangement {
        HomeArrangement(
            order: Self.split(store.string(forKey: Self.orderKey)),
            hidden: Set(Self.split(store.string(forKey: Self.hiddenKey)))
        )
    }

    /// The tiles in `order`, which ``moveTile(defaults:storedOrder:id:by:tracksWellbeing:)``
    /// or ``reorderTiles(defaults:storedOrder:listed:)`` worked out.
    public func setOrder(_ order: [String]) {
        // Absent rather than empty, so "nothing stored" reads the same whether
        // it was never written or was put back.
        store.setString(order.isEmpty ? nil : order.joined(separator: String(Self.separator)), forKey: Self.orderKey)
    }

    /// Shows `id` on the grid, or puts it behind *More*.
    public func setShown(_ id: String, _ shown: Bool) {
        var hidden = arrangement.hidden
        if shown { hidden.remove(id) } else { hidden.insert(id) }
        store.setString(
            hidden.isEmpty ? nil : hidden.sorted().joined(separator: String(Self.separator)),
            forKey: Self.hiddenKey
        )
    }

    /// Back to the default order. What is hidden stays hidden: that is one
    /// switch each.
    public func restoreDefaultOrder() {
        setOrder([])
    }

    private static func split(_ stored: String?) -> [String] {
        guard let stored else { return [] }
        return stored.split(separator: separator)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}
