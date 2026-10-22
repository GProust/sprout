package com.gproust.sprout.ui.settings

import android.content.Context
import androidx.core.content.edit
import com.gproust.sprout.data.HomeArrangement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How this phone's parent has arranged the dashboard's log grid (BDR-25): the
 * order of the tiles, and which ones wait behind *More*.
 *
 * In the ordinary settings, like [GrowthSpurtSettings] — no sync, no DB
 * migration. A restored phone keeps it, because it is about a person's habits
 * rather than about this handset ([ADR-0011][1]); it is not synced, because the
 * other parent arranges their own phone. One arrangement for every baby: with
 * twins, both panes want the same order.
 *
 * Nothing stored is the default grid, and *Restore the default order* stores
 * nothing again rather than writing today's list down — so a tile added in a
 * later release takes its default place on a phone that never moved anything.
 *
 * [1]: docs/adr/0011-what-survives-a-new-phone.md
 */
object HomeLayoutSettings {
    private const val PREFS = "settings"
    private const val KEY_ORDER = "home_tile_order"
    private const val KEY_HIDDEN = "home_tiles_hidden"

    /** Tile ids are lower-case words (`HomeTileIds`), so a comma never occurs in one. */
    private const val SEPARATOR = ","

    private val flow = MutableStateFlow(HomeArrangement())
    private var loaded = false

    /** The arrangement as stored. Reads the store once, then stays live. */
    @Synchronized
    fun arrangement(context: Context): StateFlow<HomeArrangement> {
        load(context)
        return flow.asStateFlow()
    }

    /** The tiles in [order], which `moveTile` / `reorderTiles` worked out. */
    @Synchronized
    fun setOrder(context: Context, order: List<String>) {
        load(context)
        write(context, flow.value.copy(order = order))
    }

    /** Shows [id] on the grid, or puts it behind *More*. */
    @Synchronized
    fun setShown(context: Context, id: String, shown: Boolean) {
        load(context)
        val hidden = flow.value.hidden
        write(context, flow.value.copy(hidden = if (shown) hidden - id else hidden + id))
    }

    /** Back to the default order. What is hidden stays hidden: that is one switch each. */
    @Synchronized
    fun restoreDefaultOrder(context: Context) = setOrder(context, emptyList())

    /** Forgets everything — the screenshot run starts from the default grid each time. */
    @Synchronized
    fun clear(context: Context) {
        write(context, HomeArrangement())
        loaded = true
    }

    private fun load(context: Context) {
        if (loaded) return
        val prefs = prefs(context)
        flow.value = HomeArrangement(
            order = split(prefs.getString(KEY_ORDER, null)),
            hidden = split(prefs.getString(KEY_HIDDEN, null)).toSet(),
        )
        loaded = true
    }

    private fun write(context: Context, value: HomeArrangement) {
        prefs(context).edit {
            // Absent rather than empty, so "nothing stored" reads the same
            // whether it was never written or was put back.
            if (value.order.isEmpty()) remove(KEY_ORDER)
            else putString(KEY_ORDER, value.order.joinToString(SEPARATOR))
            if (value.hidden.isEmpty()) remove(KEY_HIDDEN)
            else putString(KEY_HIDDEN, value.hidden.sorted().joinToString(SEPARATOR))
        }
        flow.value = value
    }

    private fun split(stored: String?): List<String> =
        stored?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
