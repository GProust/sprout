package com.gproust.sprout.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.MedicineWatch
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.ui.medicines.levelColor
import com.gproust.sprout.ui.medicines.levelIcon
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
 * It is on the dashboard because that is where a parent already is at 3 a.m.,
 * and "has the paracetamol had six hours yet" is a question asked far more
 * often than it is answered by opening a second screen. It says nothing the
 * as-needed screen doesn't — same sentence, same icon, same colour, from the
 * same helpers — and it is absent entirely when no wait is running, so the
 * dashboard of a household that is not in the middle of anything is unchanged.
 *
 * *Give a dose* is offered here for the same reason it is never disabled
 * there: the dose that goes unlogged is the one the parent had to leave the
 * screen to record.
 */
@Composable
fun MedicineWatchCard(
    watches: List<MedicineWatch>,
    now: Long,
    onGive: (MedicineEntity) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (watches.isEmpty()) return
    val context = LocalContext.current
    // Outlined rather than filled: on a household dashboard this sits *inside*
    // a baby's card, and two filled surfaces at the same tone would read as one.
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_medicine_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.screen_medicines),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            watches.take(WATCH_LIMIT).forEach { watch ->
                Spacer(Modifier.height(8.dp))
                WatchRow(
                    watch = watch,
                    sentence = stateSentence(context, watch.readiness, now),
                    onGive = { onGive(watch.medicine) },
                )
            }

            val hidden = watches.size - WATCH_LIMIT
            if (hidden > 0) {
                Text(
                    pluralStringResource(R.plurals.home_medicine_more, hidden, hidden),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * One medicine's line: the state three ways over — a dot, its own icon and a
 * sentence — and the dose beside it.
 *
 * The three channels are not decoration. Red/amber/green is the palette a
 * deuteranope reads worst, and this card exists to be read at a glance by
 * someone frightened and half awake (BDR-15).
 */
@Composable
private fun WatchRow(watch: MedicineWatch, sentence: String, onGive: () -> Unit) {
    val colour = levelColor(watch.readiness.level)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(10.dp)
                .background(colour, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            levelIcon(watch.readiness.level),
            // Decorative: the sentence below says the same thing, and a screen
            // reader announcing both would say it twice.
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(watch.medicine.name, style = MaterialTheme.typography.bodyMedium)
            Text(sentence, style = MaterialTheme.typography.labelMedium, color = colour)
        }
        // Never disabled, whatever the light says — the same rule the as-needed
        // screen keeps. Sprout records what happened; it does not decide it.
        TextButton(onClick = onGive) {
            Text(stringResource(R.string.medicine_give))
        }
    }
}
