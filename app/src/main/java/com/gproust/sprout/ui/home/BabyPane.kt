package com.gproust.sprout.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.common.StatCard
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatRelative
import com.gproust.sprout.ui.navigation.Routes

/**
 * Everything about one baby that isn't a history list: how long since each of
 * the three things, a feed you can start from here, the log shortcuts, and
 * today's totals.
 *
 * Shared deliberately. With one baby the dashboard shows this in place, so
 * nothing is a tap further than it used to be; with two or more the dashboard
 * shows [BabyCard] summaries and this becomes the baby's own tab. Keeping it
 * one composable is what makes those two arrangements the same screen.
 */
@Composable
fun BabyPane(
    summary: BabySummary,
    tracksWellbeing: Boolean,
    now: Long,
    onFeed: (BreastSide) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        if (header != null) {
            header()
        }

        SinceChips(summary, now)

        Spacer(Modifier.height(12.dp))
        QuickFeed(summary.nextSide, onFeed)

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.home_log))
        Spacer(Modifier.height(8.dp))
        LogGrid(tracksWellbeing, onNavigate)

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.home_today))
        Spacer(Modifier.height(8.dp))
        TodayRow(summary)

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onNavigate(Routes.STATS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.BarChart, contentDescription = null)
            Text(
                stringResource(R.string.home_see_stats),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * One baby's line on a household dashboard: the same answers as [BabyPane],
 * minus the log grid, plus a way in to the full thing.
 *
 * The feed button lives on the card rather than anywhere shared, because which
 * baby it belongs to has to be the card you touched — not a selection made on
 * another screen.
 */
@Composable
fun BabyCard(
    summary: BabySummary,
    now: Long,
    onOpen: () -> Unit,
    onFeed: (BreastSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.baby.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    babyAge(context, summary.baby.birthDate, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_open_baby, summary.baby.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            SinceChips(summary, now)

            Spacer(Modifier.height(12.dp))
            QuickFeed(summary.nextSide, onFeed)
        }
    }
}

/**
 * "Fed 2h ago · Slept 40m ago · Nappy 1h ago" — the questions the app is opened
 * to answer, in the order they get asked.
 *
 * A chip with nothing behind it is left out rather than shown empty: no feed in
 * the last week is better said by silence than by a dash.
 */
@Composable
fun SinceChips(summary: BabySummary, now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        summary.lastFeed?.let {
            SinceChip(
                Icons.Filled.LocalDrink,
                stringResource(R.string.home_chip_fed, formatRelative(context, it, now)),
            )
        }
        summary.lastSleep?.let {
            SinceChip(
                Icons.Filled.Bedtime,
                stringResource(R.string.home_chip_slept, formatRelative(context, it, now)),
            )
        }
        summary.lastDiaper?.let {
            SinceChip(
                Icons.Filled.BabyChangingStation,
                stringResource(R.string.home_chip_nappy, formatRelative(context, it, now)),
            )
        }
    }
}

@Composable
private fun SinceChip(icon: ImageVector, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

/**
 * Start a breastfeed without going through the feeding screen first.
 *
 * [next] is the breast the last session did *not* begin on, so the suggested
 * one is filled and the other stays available beside it — the alternation is a
 * default, never a rule. With nothing nursed in the last day there is no
 * alternation to continue, and both sides are offered evenly.
 */
@Composable
fun QuickFeed(next: BreastSide?, onFeed: (BreastSide) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (next) {
            BreastSide.LEFT, BreastSide.RIGHT -> {
                val other =
                    if (next == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT
                Button(onClick = { onFeed(next) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(startLabel(next)))
                }
                OutlinedButton(onClick = { onFeed(other) }) {
                    Text(stringResource(sideLabel(other)))
                }
            }
            else -> {
                Button(
                    onClick = { onFeed(BreastSide.LEFT) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.feeding_start_left))
                }
                Button(
                    onClick = { onFeed(BreastSide.RIGHT) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.feeding_start_right))
                }
            }
        }
    }
}

private fun startLabel(side: BreastSide) = when (side) {
    BreastSide.RIGHT -> R.string.feeding_start_right
    else -> R.string.feeding_start_left
}

private fun sideLabel(side: BreastSide) = when (side) {
    BreastSide.RIGHT -> R.string.side_right
    BreastSide.BOTH -> R.string.side_both
    else -> R.string.side_left
}

/**
 * The six things there are to log, as tiles rather than a stack of identical
 * buttons. Wellbeing joins them only for a parent who tracks it; treatments is
 * here rather than in the bottom bar, where there was never a seat for it.
 */
@Composable
fun LogGrid(tracksWellbeing: Boolean, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val tiles = buildList {
        add(Tile(R.string.nav_feed, Icons.Filled.LocalDrink, Routes.FEEDING))
        add(Tile(R.string.screen_pumping, Icons.Filled.WaterDrop, Routes.PUMPING))
        add(Tile(R.string.nav_sleep, Icons.Filled.Bedtime, Routes.SLEEP))
        add(Tile(R.string.nav_diaper, Icons.Filled.BabyChangingStation, Routes.DIAPER))
        add(Tile(R.string.nav_growth, Icons.Filled.Monitor, Routes.GROWTH))
        add(Tile(R.string.screen_treatments, Icons.Filled.Medication, Routes.TREATMENTS))
        if (tracksWellbeing) {
            add(Tile(R.string.screen_wellbeing, Icons.Filled.Favorite, Routes.HEALTH))
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    LogTile(tile, Modifier.weight(1f), onNavigate)
                }
                // Keeps a short last row's tiles the same width as a full one's
                // rather than letting three columns become one wide button.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class Tile(val labelRes: Int, val icon: ImageVector, val route: String)

@Composable
private fun LogTile(tile: Tile, modifier: Modifier, onNavigate: (String) -> Unit) {
    OutlinedCard(
        modifier = modifier.clickable { onNavigate(tile.route) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                tile.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                stringResource(tile.labelRes),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TodayRow(summary: BabySummary) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            label = stringResource(R.string.stat_feeds),
            value = summary.feedsToday.toString(),
            icon = Icons.Filled.LocalDrink,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.stat_sleep),
            value = formatDuration(context, summary.sleepTodayMs),
            icon = Icons.Filled.Bedtime,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.stat_diapers),
            value = summary.diapersToday.toString(),
            icon = Icons.Filled.BabyChangingStation,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * The gentle growth-spurt note, shown while a baby is in — or a few days from —
 * one of the typical periods. Informational only; every baby is different.
 */
@Composable
fun GrowthSpurtNote(spurt: GrowthSpurtUi, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.TrendingUp, contentDescription = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(
                        if (spurt.startsSoon) R.string.growth_spurt_soon_title
                        else R.string.growth_spurt_now_title,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (spurt.startsSoon) R.string.growth_spurt_soon_body
                        else R.string.growth_spurt_now_body,
                        spurt.ageLabel,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
