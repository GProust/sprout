package com.gproust.sprout.ui.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.gproust.sprout.R
import com.gproust.sprout.data.HomeTileIds
import com.gproust.sprout.ui.navigation.Routes

/**
 * One of the dashboard's log tiles: what it says, its icon, and where it goes.
 *
 * One definition for the three places a tile appears — the grid, the *More*
 * menu and *Customise home* — so a log reads the same in all of them.
 */
data class HomeTile(
    val id: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val route: String,
)

/**
 * The tile a stored id names (BDR-25), or null for an id this version does not
 * know — which `tileOrder` has already dropped, so in practice never.
 */
fun homeTile(id: String): HomeTile? = when (id) {
    HomeTileIds.FEEDING -> HomeTile(id, R.string.nav_feed, Icons.Filled.LocalDrink, Routes.FEEDING)
    HomeTileIds.PUMPING -> HomeTile(id, R.string.screen_pumping, Icons.Filled.WaterDrop, Routes.PUMPING)
    HomeTileIds.SLEEP -> HomeTile(id, R.string.nav_sleep, Icons.Filled.Bedtime, Routes.SLEEP)
    HomeTileIds.DIAPER -> HomeTile(id, R.string.nav_diaper, Icons.Filled.BabyChangingStation, Routes.DIAPER)
    HomeTileIds.GROWTH -> HomeTile(id, R.string.nav_growth, Icons.Filled.Monitor, Routes.GROWTH)
    HomeTileIds.TREATMENTS -> HomeTile(id, R.string.screen_treatments, Icons.Filled.Medication, Routes.TREATMENTS)
    HomeTileIds.MEDICINES -> HomeTile(id, R.string.screen_medicines, Icons.Filled.Vaccines, Routes.MEDICINES)
    HomeTileIds.FOODS -> HomeTile(id, R.string.screen_foods, Icons.Filled.Restaurant, Routes.FOODS)
    HomeTileIds.WELLBEING -> HomeTile(id, R.string.screen_wellbeing, Icons.Filled.Favorite, Routes.HEALTH)
    else -> null
}
