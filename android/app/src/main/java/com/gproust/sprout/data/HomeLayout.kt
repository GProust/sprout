package com.gproust.sprout.data

/**
 * The stable id each of the dashboard's log tiles is stored under (BDR-25).
 *
 * They are the names iOS's tiles already carried in their accessibility
 * identifiers (`log-tile-<name>`), and one set for both apps: the arrangement a
 * parent makes is stored by these, so an id is never renamed or reused. A log
 * added later appends its own.
 */
object HomeTileIds {
    const val FEEDING = "feeding"
    const val PUMPING = "pumping"
    const val SLEEP = "sleep"
    const val DIAPER = "diaper"
    const val GROWTH = "growth"
    const val TREATMENTS = "treatments"
    const val MEDICINES = "medicines"
    const val FOODS = "foods"

    /** The one tile with a master switch of its own (BDR-6). */
    const val WELLBEING = "wellbeing"
}

/**
 * Every tile, in the order a phone nobody has arranged draws them — which is the
 * order the grid had before it could be arranged at all, so a parent who never
 * opens *Customise home* sees no change. The same list as `homeTileIds` in
 * `SproutData/HomeLayout.swift`.
 */
val HOME_TILE_IDS: List<String> = listOf(
    HomeTileIds.FEEDING,
    HomeTileIds.PUMPING,
    HomeTileIds.SLEEP,
    HomeTileIds.DIAPER,
    HomeTileIds.GROWTH,
    HomeTileIds.TREATMENTS,
    HomeTileIds.MEDICINES,
    HomeTileIds.FOODS,
    HomeTileIds.WELLBEING,
)

/**
 * What this phone's parent has made of the grid: an order, and the tiles put
 * away behind *More*. Empty is "nothing stored", which is the default grid.
 */
data class HomeArrangement(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
)

/** What the grid draws: the tiles shown, in order, and the ones waiting behind *More*. */
data class HomeTiles(val shown: List<String>, val hidden: List<String>)

/**
 * Every tile in [defaults], once each, in the order [stored] puts them.
 *
 * Two things a stored order can be out of step with, because it was written by
 * another version of the app:
 * - **A tile it does not name** — a log added after the parent last arranged
 *   the grid — goes in at its place in the default order, counted from the
 *   start. It is new to them, and it is the app's to place until they move it.
 * - **An id the app no longer knows** is dropped. A stored list is never a
 *   reason for a tile to exist.
 */
fun tileOrder(defaults: List<String>, stored: List<String>): List<String> {
    val known = defaults.toSet()
    val order = stored.filter { it in known }.distinct().toMutableList()
    defaults.forEachIndexed { index, id ->
        if (id !in order) order.add(minOf(index, order.size), id)
    }
    return order
}

/**
 * The tiles *Customise home* lists, in order: all of them, except wellbeing for
 * a parent who has turned their own tracking off (BDR-6) — the tile is not in
 * the grid then, and an editor offering to show it would be offering nothing.
 */
fun listedTiles(
    defaults: List<String>,
    storedOrder: List<String>,
    tracksWellbeing: Boolean,
): List<String> = tileOrder(defaults, storedOrder)
    .filter { tracksWellbeing || it != HomeTileIds.WELLBEING }

/**
 * The grid as this parent arranged it.
 *
 * Hiding is presentation only: a hidden tile is drawn behind *More* rather than
 * not at all, so its screen stays one tap away. A tile the parent never touched
 * is shown — nothing is hidden that they did not hide.
 */
fun arrangeTiles(
    defaults: List<String>,
    storedOrder: List<String>,
    hidden: Set<String>,
    tracksWellbeing: Boolean,
): HomeTiles {
    val listed = listedTiles(defaults, storedOrder, tracksWellbeing)
    return HomeTiles(
        shown = listed.filter { it !in hidden },
        hidden = listed.filter { it in hidden },
    )
}

/**
 * The order to store once the editor's list reads [listed].
 *
 * Only the listed tiles move: they are put back into the places they held
 * between them, in their new order, and anything the editor was not showing —
 * wellbeing while it is switched off — keeps its own place. That is what lets
 * the tile come back where the parent had put it when tracking is turned on
 * again. A [listed] that names a tile twice, or one the app does not know, is
 * not a rearrangement of anything and changes nothing.
 */
fun reorderTiles(
    defaults: List<String>,
    storedOrder: List<String>,
    listed: List<String>,
): List<String> {
    val order = tileOrder(defaults, storedOrder)
    val slots = listed.toSet()
    if (listed.size != slots.size || !order.containsAll(slots)) return order
    val incoming = listed.iterator()
    return order.map { id -> if (id in slots) incoming.next() else id }
}

/**
 * The order to store after [id] is moved [offset] places in the editor's list:
 * -1 is *Move up*, +1 *Move down*. A move past either end stops there.
 */
fun moveTile(
    defaults: List<String>,
    storedOrder: List<String>,
    id: String,
    offset: Int,
    tracksWellbeing: Boolean,
): List<String> {
    val listed = listedTiles(defaults, storedOrder, tracksWellbeing).toMutableList()
    val from = listed.indexOf(id)
    if (from < 0) return tileOrder(defaults, storedOrder)
    val to = (from + offset).coerceIn(0, listed.lastIndex)
    listed.add(to, listed.removeAt(from))
    return reorderTiles(defaults, storedOrder, listed)
}
