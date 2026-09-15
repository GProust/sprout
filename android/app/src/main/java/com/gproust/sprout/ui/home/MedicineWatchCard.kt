package com.gproust.sprout.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.MedicineWatch
import com.gproust.sprout.ui.medicines.levelColor
import com.gproust.sprout.ui.medicines.levelIcon
import com.gproust.sprout.ui.medicines.shortState
import com.gproust.sprout.ui.medicines.stateSentence

/**
 * How many medicines the dashboard will show before it stops being a glance.
 *
 * A household with more waits running than this has a screen for them, and the
 * card says how many it is not showing rather than growing to fit.
 */
private const val WATCH_LIMIT = 3

/**
 * The as-needed medicines with a wait running, or one that has just finished
 * (BDR-16).
 *
 * **One line each, and no more.** The first version of this said everything the
 * As needed screen says — name, dose, interval, the full state sentence, the
 * last dose and its count — and the first thing users said back was that the
 * dashboard had got heavy. They were right: this is a glance at something that
 * is usually not happening, sitting on the screen that is opened every hour. So
 * a line is the name in its state's colour, the number still to wait if there is
 * one, and the two things there are to do about it.
 *
 * It keeps its place under the feed buttons rather than above them, for the same
 * reason: feeding is what the dashboard is opened for, and a medicine is what it
 * is opened for a few days a year.
 *
 * Everything the line drops is a tap away on the As needed screen — and none of
 * it is dropped for a screen reader, which is given the full sentence.
 */
@Composable
fun MedicineWatchCard(
    watches: List<MedicineWatch>,
    now: Long,
    onGive: (MedicineWatch) -> Unit,
    onDismiss: (MedicineWatch) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (watches.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            watches.take(WATCH_LIMIT).forEach { watch ->
                WatchRow(
                    watch = watch,
                    now = now,
                    onOpen = onOpen,
                    onGive = { onGive(watch) },
                    onDismiss = { onDismiss(watch) },
                )
            }

            val hidden = watches.size - WATCH_LIMIT
            if (hidden > 0) {
                Text(
                    pluralStringResource(R.plurals.home_medicine_more, hidden, hidden),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onOpen)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * One medicine, one line: `⏳ Paracetamol  4 h 12 m to wait   Give  ✕`.
 *
 * The state is carried three ways over — the icon's shape, the colour, and the
 * words of the countdown — because red/amber/green is the palette a deuteranope
 * reads worst and this is read at 3 a.m. by someone frightened (BDR-15). The
 * icon is what distinguishes *can be given* from *can be given, sooner than
 * ideal* once there is no number left to print.
 */
@Composable
private fun WatchRow(
    watch: MedicineWatch,
    now: Long,
    onOpen: () -> Unit,
    onGive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colour = levelColor(watch.readiness.level)
    val state = shortState(context, watch.readiness, now)

    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp)
                // One utterance, and the long sentence rather than the short
                // one: the line is abbreviated because it is being *looked* at,
                // and none of that applies to a screen reader.
                .clearAndSetSemantics {
                    contentDescription =
                        "${watch.medicine.name}. ${stateSentence(context, watch.readiness, now)}"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                levelIcon(watch.readiness.level),
                contentDescription = null,
                tint = colour,
                modifier = Modifier.size(18.dp),
            )
            Text(
                watch.medicine.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            )
            if (state != null) {
                Text(
                    state,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Spacer(Modifier.width(4.dp))
        // Both trimmed in: a line has a name on it, and the name is what has to
        // survive a narrow screen. They keep their 48 dp touch targets — only
        // the padding around the words comes out.
        //
        // Never disabled, whatever the light says — the same rule the As needed
        // screen keeps. Sprout records what happened; it does not decide it.
        TextButton(
            onClick = onGive,
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Text(stringResource(R.string.medicine_give_short))
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.medicine_dismiss),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
