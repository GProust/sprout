package com.gproust.sprout.ui.foods

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoFood
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.FoodHistory
import com.gproust.sprout.data.local.AllergyEntity
import com.gproust.sprout.data.local.MealFood
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.formatDate

/** What the allergy is to, in this phone's language when it is a food from the list. */
fun AllergyEntity.displayName(context: Context): String = catalogName(context, ref) ?: name

/** "Confirmed by a professional" or "Noted by the family" — the parent's own statement, said back. */
fun AllergyEntity.statusLabel(context: Context): String = context.getString(
    if (confirmed) R.string.allergy_confirmed_short else R.string.allergy_noted_short,
)

/**
 * The mark beside a food the baby is noted as allergic to (BDR-21): an icon and
 * the words, never a colour alone and never a warning colour — it restates
 * what the parent wrote down, it does not raise an alarm of its own.
 */
@Composable
fun AllergyMarker(modifier: Modifier = Modifier) {
    val text = stringResource(R.string.allergy_marker)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
    ) {
        Icon(
            Icons.Filled.NoFood,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The baby's allergies at the top of the First foods page: each one opens to
 * correct, *Note an allergy* adds one, and *Share the list* hands the list on
 * its own to whoever needs it — a doctor, a nursery, a grandparent.
 */
@Composable
fun AllergiesCard(
    allergies: List<AllergyEntity>,
    onAdd: () -> Unit,
    onOpen: (AllergyEntity) -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NoFood, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.allergies_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (allergies.isEmpty()) {
                Text(
                    stringResource(R.string.allergies_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            allergies.forEach { allergy ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(allergy) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(allergy.displayName(context), style = MaterialTheme.typography.titleSmall)
                    Text(
                        allergyDetails(context, allergy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.allergy_add))
            }
            if (allergies.isNotEmpty()) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.allergies_share))
                }
            }
        }
    }
}

/** "Confirmed by a professional · Noticed 3 Sep 2026" — whichever parts were given. */
fun allergyDetails(context: Context, allergy: AllergyEntity): String {
    val sep = " ${context.getString(R.string.feeding_detail_separator).trim()} "
    return listOfNotNull(
        allergy.statusLabel(context),
        allergy.noticedAt?.let { context.getString(R.string.allergy_noticed_line, formatDate(context, it)) },
    ).joinToString(sep)
}

/**
 * Noting an allergy, or correcting one (BDR-21): what it is to — picked with
 * the same search the meal form uses, so a carrot from the list is the carrot
 * on every meal — and, optionally, when it was noticed, whether a health
 * professional confirmed it, and what was seen.
 *
 * Nothing is suggested. The form never proposes a food because it was
 * refused, and it never asks about symptoms: what the parent saw goes in the
 * notes, in their words.
 */
@Composable
fun AllergyForm(
    initial: AllergyEntity?,
    known: List<FoodHistory>,
    onSave: (AllergyEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var food by remember(initial?.uid) {
        mutableStateOf(initial?.let { MealFood(it.displayName(context), ref = it.ref) })
    }
    var noticedAt by remember(initial?.uid) { mutableStateOf(initial?.noticedAt) }
    var confirmed by remember(initial?.uid) { mutableStateOf(initial?.confirmed ?: false) }
    var notes by remember(initial?.uid) { mutableStateOf(initial?.notes.orEmpty()) }

    FieldLabel(stringResource(R.string.allergy_what))
    val chosen = food
    if (chosen == null) {
        FoodSearch(known = known, hidden = emptySet(), onPick = { food = it })
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chosen.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { food = null }) { Text(stringResource(R.string.food_correct_change)) }
        }
    }

    Spacer(Modifier.height(8.dp))
    val date = noticedAt
    if (date == null) {
        OutlinedButton(onClick = { noticedAt = System.currentTimeMillis() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.allergy_noticed_add))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DatePickerField(
                    label = stringResource(R.string.allergy_noticed),
                    millis = date,
                    onChange = { noticedAt = it },
                    allowFuture = false,
                )
            }
            IconButton(onClick = { noticedAt = null }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.allergy_noticed_clear))
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { confirmed = !confirmed }.padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.allergy_confirmed), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.allergy_confirmed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = confirmed, onCheckedChange = { confirmed = it })
    }

    NotesField(value = notes, onChange = { notes = it })
    Text(
        stringResource(R.string.allergy_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            enabled = chosen != null,
            onClick = {
                val picked = chosen ?: return@Button
                val base = initial ?: AllergyEntity(name = picked.name)
                onSave(
                    base.copy(
                        name = picked.name,
                        ref = picked.ref,
                        noticedAt = noticedAt,
                        confirmed = confirmed,
                        notes = notes.trim().ifEmpty { null },
                    ),
                )
            },
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}
