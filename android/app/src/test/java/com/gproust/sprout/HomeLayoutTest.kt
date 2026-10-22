package com.gproust.sprout

import com.gproust.sprout.data.HOME_TILE_IDS
import com.gproust.sprout.data.HomeTileIds.DIAPER
import com.gproust.sprout.data.HomeTileIds.FEEDING
import com.gproust.sprout.data.HomeTileIds.FOODS
import com.gproust.sprout.data.HomeTileIds.GROWTH
import com.gproust.sprout.data.HomeTileIds.MEDICINES
import com.gproust.sprout.data.HomeTileIds.PUMPING
import com.gproust.sprout.data.HomeTileIds.SLEEP
import com.gproust.sprout.data.HomeTileIds.TREATMENTS
import com.gproust.sprout.data.HomeTileIds.WELLBEING
import com.gproust.sprout.data.HomeTiles
import com.gproust.sprout.data.arrangeTiles
import com.gproust.sprout.data.listedTiles
import com.gproust.sprout.data.moveTile
import com.gproust.sprout.data.reorderTiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the dashboard's log grid is arranged from what a parent stored (BDR-25).
 *
 * The same cases are checked on iOS (`HomeLayoutTests.swift`). An arrangement
 * never leaves the phone it was made on, but the rule is one product's: a log
 * added in a later release has to land in the same place on both apps, and a
 * hidden one has to stay hidden on both.
 */
class HomeLayoutTest {

    private val withoutWellbeing = HOME_TILE_IDS - WELLBEING

    /** Stored, so pinned: renaming one would lose where every parent had put it. */
    @Test
    fun theIdsAreTheOnesStored() {
        assertEquals(
            listOf(
                "feeding", "pumping", "sleep", "diaper", "growth",
                "treatments", "medicines", "foods", "wellbeing",
            ),
            HOME_TILE_IDS,
        )
    }

    /** A parent who never opens *Customise home* sees the grid they always had, and no More. */
    @Test
    fun nothingStoredIsTheDefaultGrid() {
        assertEquals(
            HomeTiles(shown = HOME_TILE_IDS, hidden = emptyList()),
            arrangeTiles(HOME_TILE_IDS, emptyList(), emptySet(), tracksWellbeing = true),
        )
    }

    @Test
    fun aStoredOrderIsFollowed() {
        val stored = listOf(SLEEP, FEEDING, DIAPER, PUMPING, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING)
        assertEquals(stored, arrangeTiles(HOME_TILE_IDS, stored, emptySet(), true).shown)
    }

    /** First foods arriving on a phone whose order was stored before it existed. */
    @Test
    fun aNewTileAppearsShownAtItsDefaultPlace() {
        val beforeFoods = listOf(FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES, WELLBEING)
        assertEquals(
            HomeTiles(shown = HOME_TILE_IDS, hidden = emptyList()),
            arrangeTiles(HOME_TILE_IDS, beforeFoods, emptySet(), true),
        )

        // Its place in the default order, counted from the start, whatever the
        // parent has done with the others — and shown, whatever they hid.
        val rearranged = listOf(MEDICINES, FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, WELLBEING)
        assertEquals(
            HomeTiles(
                shown = listOf(MEDICINES, FEEDING, SLEEP, DIAPER, GROWTH, TREATMENTS, FOODS, WELLBEING),
                hidden = listOf(PUMPING),
            ),
            arrangeTiles(HOME_TILE_IDS, rearranged, setOf(PUMPING), true),
        )
    }

    @Test
    fun aNewTileAtTheEndOfTheDefaultsGoesAtTheEnd() {
        val defaults = HOME_TILE_IDS + "bath"
        assertEquals(
            HOME_TILE_IDS.reversed() + "bath",
            arrangeTiles(defaults, HOME_TILE_IDS.reversed(), emptySet(), true).shown,
        )
    }

    /** Written by a version that had a tile this one does not, or written twice. */
    @Test
    fun anUnknownOrRepeatedIdIsIgnored() {
        val stored = listOf(SLEEP, "nursery-notes", FEEDING, SLEEP)
        assertEquals(
            HomeTiles(
                shown = listOf(SLEEP, FEEDING, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING),
                hidden = listOf(PUMPING),
            ),
            arrangeTiles(HOME_TILE_IDS, stored, setOf("nursery-notes", PUMPING), true),
        )
    }

    /** Off is off everywhere: not in the grid, not behind More, not in the editor (BDR-6). */
    @Test
    fun wellbeingOffLeavesTheGridMoreAndTheEditor() {
        assertEquals(
            HomeTiles(shown = withoutWellbeing, hidden = emptyList()),
            arrangeTiles(HOME_TILE_IDS, emptyList(), emptySet(), tracksWellbeing = false),
        )
        assertEquals(
            HomeTiles(shown = withoutWellbeing - PUMPING, hidden = listOf(PUMPING)),
            arrangeTiles(HOME_TILE_IDS, emptyList(), setOf(PUMPING, WELLBEING), tracksWellbeing = false),
        )
        assertEquals(withoutWellbeing, listedTiles(HOME_TILE_IDS, emptyList(), tracksWellbeing = false))
    }

    /** Turned back on, it is where the parent had put it, shown or hidden as it was. */
    @Test
    fun wellbeingComesBackWhereItWas() {
        val stored = listOf(FEEDING, WELLBEING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS)

        // Rearranged while it was off: First foods to the top of the editor.
        val moved = moveTile(HOME_TILE_IDS, stored, FOODS, -7, tracksWellbeing = false)
        assertEquals(
            listOf(FOODS, FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES),
            listedTiles(HOME_TILE_IDS, moved, tracksWellbeing = false),
        )

        assertEquals(
            HomeTiles(
                shown = listOf(FOODS, WELLBEING, FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES),
                hidden = emptyList(),
            ),
            arrangeTiles(HOME_TILE_IDS, moved, emptySet(), tracksWellbeing = true),
        )
        assertEquals(
            HomeTiles(
                shown = listOf(FOODS, FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES),
                hidden = listOf(WELLBEING),
            ),
            arrangeTiles(HOME_TILE_IDS, moved, setOf(WELLBEING), tracksWellbeing = true),
        )
    }

    /** The grid is then the More tile alone, and every screen is still one tap away. */
    @Test
    fun everythingHiddenLeavesEverythingBehindMore() {
        assertEquals(
            HomeTiles(shown = emptyList(), hidden = HOME_TILE_IDS),
            arrangeTiles(HOME_TILE_IDS, emptyList(), HOME_TILE_IDS.toSet(), true),
        )
    }

    /** Hidden is not forgotten: shown again, a tile is back in the place it held. */
    @Test
    fun aHiddenTileKeepsItsPlace() {
        val stored = listOf(SLEEP, PUMPING, FEEDING, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING)
        assertEquals(
            stored - PUMPING,
            arrangeTiles(HOME_TILE_IDS, stored, setOf(PUMPING), true).shown,
        )
        assertEquals(stored, arrangeTiles(HOME_TILE_IDS, stored, emptySet(), true).shown)
    }

    @Test
    fun moveUpAndMoveDownSwapNeighbours() {
        assertEquals(
            listOf(PUMPING, FEEDING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING),
            moveTile(HOME_TILE_IDS, emptyList(), PUMPING, -1, true),
        )
        assertEquals(
            listOf(FEEDING, SLEEP, PUMPING, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING),
            moveTile(HOME_TILE_IDS, emptyList(), PUMPING, 1, true),
        )
    }

    /** A hidden tile is listed in the editor like any other, and moves like one. */
    @Test
    fun aHiddenTileCanBeMoved() {
        val moved = moveTile(HOME_TILE_IDS, emptyList(), FOODS, -7, true)
        assertEquals(
            HomeTiles(
                shown = listOf(FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES, WELLBEING),
                hidden = listOf(FOODS),
            ),
            arrangeTiles(HOME_TILE_IDS, moved, setOf(FOODS), true),
        )
        assertEquals(FOODS, arrangeTiles(HOME_TILE_IDS, moved, emptySet(), true).shown.first())
    }

    @Test
    fun aMovePastEitherEndStopsThere() {
        assertEquals(HOME_TILE_IDS, moveTile(HOME_TILE_IDS, emptyList(), FEEDING, -1, true))
        assertEquals(HOME_TILE_IDS, moveTile(HOME_TILE_IDS, emptyList(), WELLBEING, 1, true))
        assertEquals(
            listOf(FEEDING, PUMPING, SLEEP, DIAPER, GROWTH, TREATMENTS, MEDICINES, WELLBEING, FOODS),
            moveTile(HOME_TILE_IDS, emptyList(), FOODS, 20, true),
        )
    }

    /** Neither wellbeing while it is off nor an id the app does not know can be moved. */
    @Test
    fun aTileTheEditorDoesNotListDoesNotMove() {
        assertEquals(HOME_TILE_IDS, moveTile(HOME_TILE_IDS, emptyList(), WELLBEING, -1, tracksWellbeing = false))
        assertEquals(HOME_TILE_IDS, moveTile(HOME_TILE_IDS, emptyList(), "nursery-notes", -1, true))
        // And the last tile the editor lists, which is not the last stored.
        assertEquals(HOME_TILE_IDS, moveTile(HOME_TILE_IDS, emptyList(), FOODS, 1, tracksWellbeing = false))
    }

    /** iOS's drag: the editor's whole list in a new order. */
    @Test
    fun aRearrangedListIsStoredInItsNewOrder() {
        val listed = listOf(SLEEP, FEEDING, PUMPING, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS)
        assertEquals(
            listOf(SLEEP, FEEDING, PUMPING, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING),
            reorderTiles(HOME_TILE_IDS, emptyList(), listed),
        )
    }

    /** Anything but a rearrangement of what the editor showed changes nothing. */
    @Test
    fun aListThatIsNotARearrangementChangesNothing() {
        val stored = listOf(SLEEP, FEEDING, PUMPING, DIAPER, GROWTH, TREATMENTS, MEDICINES, FOODS, WELLBEING)
        assertEquals(stored, reorderTiles(HOME_TILE_IDS, stored, listOf(FEEDING, FEEDING)))
        assertEquals(stored, reorderTiles(HOME_TILE_IDS, stored, listOf("nursery-notes", FEEDING)))
    }

    /** *Restore the default order* forgets the order, and only the order. */
    @Test
    fun restoringTheDefaultOrderKeepsWhatIsHidden() {
        assertEquals(
            HomeTiles(
                shown = listOf(FEEDING, SLEEP, DIAPER, GROWTH, MEDICINES, FOODS, WELLBEING),
                hidden = listOf(PUMPING, TREATMENTS),
            ),
            arrangeTiles(HOME_TILE_IDS, emptyList(), setOf(TREATMENTS, PUMPING), true),
        )
    }
}
