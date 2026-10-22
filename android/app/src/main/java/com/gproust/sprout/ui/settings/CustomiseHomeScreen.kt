package com.gproust.sprout.ui.settings

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.SproutApplication
import com.gproust.sprout.data.HOME_TILE_IDS
import com.gproust.sprout.data.listedTiles
import com.gproust.sprout.data.moveTile
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.help.HelpButton
import com.gproust.sprout.ui.help.HelpPage
import com.gproust.sprout.ui.home.homeTile

/**
 * *Customise home*: which logs the dashboard's grid shows, and in which order
 * (BDR-25).
 *
 * A list rather than tiles to drag about in the grid itself. Compose has no
 * reorderable grid and Sprout takes no dependency for one, but the deciding
 * reason is that a drag is the one gesture TalkBack cannot make — so each row
 * moves with two buttons that a finger, Switch Access and TalkBack can all
 * press, and offers the same two moves as accessibility actions.
 *
 * Nothing here is suggested. The list starts as the grid is, every switch on
 * until the parent turns one off, and nothing on the screen knows which logs
 * are used and which are not.
 */
@Composable
fun CustomiseHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val repository = remember { (context.applicationContext as SproutApplication).repository }
    val profile by repository.parentProfile.collectAsState(initial = null)
    val arrangement by HomeLayoutSettings.arrangement(context).collectAsState()
    // As the dashboard reads it: on until the profile says otherwise, so the
    // wellbeing row does not blink in once the profile has loaded.
    val tracksWellbeing = profile?.trackWellbeing != false
    val listed = listedTiles(HOME_TILE_IDS, arrangement.order, tracksWellbeing)

    fun move(id: String, label: String, offset: Int) {
        val order = moveTile(HOME_TILE_IDS, arrangement.order, id, offset, tracksWellbeing)
        HomeLayoutSettings.setOrder(context, order)
        val now = listedTiles(HOME_TILE_IDS, order, tracksWellbeing)
        announce(view, context, label, now.indexOf(id) + 1, now.size)
    }

    Scaffold(
        // A part of Settings, so it opens Settings' sheet, which says what
        // this page does (BDR-26).
        topBar = {
            SproutTopBar(
                stringResource(R.string.screen_customise_home),
                onBack = onBack,
                actions = { HelpButton(HelpPage.SETTINGS) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.customise_home_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Keyed by tile, so a row that moves is the same row afterwards —
            // and the screen reader's focus moves with it rather than staying
            // on whatever slid into its place.
            itemsIndexed(listed, key = { _, id -> id }) { index, id ->
                homeTile(id)?.let { tile ->
                    val label = stringResource(tile.labelRes)
                    TileRow(
                        label = label,
                        icon = tile.icon,
                        shown = id !in arrangement.hidden,
                        canMoveUp = index > 0,
                        canMoveDown = index < listed.lastIndex,
                        onShownChange = { shown -> HomeLayoutSettings.setShown(context, id, shown) },
                        onMove = { offset -> move(id, label, offset) },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                OutlinedButton(
                    onClick = { HomeLayoutSettings.restoreDefaultOrder(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.customise_home_restore))
                }
            }
        }
    }
}

/**
 * One log: its switch, and the two moves.
 *
 * The whole row is the switch, so TalkBack reads one thing — "Pumping, shown on
 * home" — rather than a label and a separate unnamed toggle; the moves ride on
 * it as custom actions as well as sitting beside it as buttons.
 */
@Composable
private fun TileRow(
    label: String,
    icon: ImageVector,
    shown: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onShownChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val moveUp = stringResource(R.string.customise_home_move_up)
    val moveDown = stringResource(R.string.customise_home_move_down)
    val state = stringResource(
        if (shown) R.string.customise_home_shown else R.string.customise_home_hidden,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = shown, role = Role.Switch, onValueChange = onShownChange)
            .semantics {
                stateDescription = state
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(moveUp) { onMove(-1); true })
                    if (canMoveDown) add(CustomAccessibilityAction(moveDown) { onMove(1); true })
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = moveUp)
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = moveDown)
        }
        // Drawn only: the row above is what toggles, and a second toggleable
        // inside it would be a second thing for TalkBack to stop on.
        Switch(checked = shown, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Says where a moved log has landed — "Pumping, position 3 of 8".
 *
 * A row that moves keeps TalkBack's focus but is not read again, so without
 * this a move up is a double tap followed by silence. `announceForAccessibility`
 * is deprecated from API 36 in favour of live regions, but a live region on the
 * rows would speak for every row a move shifts, not just the one that moved;
 * and below API 36 it is still the call that works.
 */
@Suppress("DEPRECATION")
private fun announce(view: View, context: Context, label: String, position: Int, count: Int) {
    if (position < 1) return
    view.announceForAccessibility(
        context.getString(R.string.customise_home_moved, label, position, count),
    )
}
