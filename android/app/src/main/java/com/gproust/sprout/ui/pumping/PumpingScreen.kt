package com.gproust.sprout.ui.pumping

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.ui.common.AddEntryFab
import com.gproust.sprout.ui.common.AddEntrySheet
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.ConfirmDeleteDialog
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.DayHeader
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.StatCard
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.common.startOfDay
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PumpingViewModel(private val repository: SproutRepository) : ViewModel() {
    val entries = repository.pumpings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: PumpingEntity) = viewModelScope.launch { repository.addPumping(entity) }
    fun delete(entity: PumpingEntity) = viewModelScope.launch { repository.deletePumping(entity) }
}

/**
 * Expressed milk: what is in the stash right now, on top of the history of
 * every pumping session (newest first, grouped by day). A stored batch is one
 * tap from being marked used once the baby has drunk it; tapping the entry
 * itself re-opens it, which is how a bottle moves from the fridge to the
 * freezer or gets corrected.
 */
@Composable
fun PumpingScreen(onBack: () -> Unit) {
    val vm: PumpingViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val entries by vm.entries.collectAsState()
    val context = LocalContext.current
    val now = remember(entries) { System.currentTimeMillis() }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PumpingEntity?>(null) }
    // The entry awaiting a "yes, delete it" — deletes are permanent.
    var deleting by remember { mutableStateOf<PumpingEntity?>(null) }

    // Giving a bottle is the most common thing to do to a stored batch, so it
    // is one tap from the list rather than a trip through the editor. Nothing
    // is lost either way: the entry keeps its amount and time, only its place
    // changes — and the snackbar puts it back where it was, mistap or not.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val markedMessage = stringResource(R.string.pumping_marked_used)
    val undoLabel = stringResource(R.string.action_undo)
    fun markUsed(entry: PumpingEntity) {
        vm.add(entry.copy(storage = MilkStorage.USED))
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = markedMessage,
                actionLabel = undoLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.add(entry)
        }
    }

    if (adding) {
        AddEntrySheet(
            title = stringResource(R.string.pumping_log_title),
            onDismiss = { adding = false },
        ) {
            PumpingForm(
                initial = null,
                submitLabel = stringResource(R.string.pumping_add),
                onSubmit = { vm.add(it); adding = false },
            )
        }
    }

    editing?.let { entry ->
        EditPumpingDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { vm.add(it); editing = null },
            onDelete = { deleting = entry },
        )
    }

    // Sits on top of the editor when the delete came from there, so backing out
    // of the confirmation leaves you where you were.
    deleting?.let { entry ->
        ConfirmDeleteDialog(
            onConfirm = { vm.delete(entry); deleting = null; editing = null },
            onDismiss = { deleting = null },
        )
    }

    val stash = remember(entries, now) { milkStash(entries, now) }
    val pumpedToday = remember(entries, now) { pumpedSince(entries, startOfDay(now)) }
    val byDay = remember(entries) { entries.groupBy { startOfDay(it.time) } }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_pumping), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AddEntryFab(stringResource(R.string.pumping_log_title)) { adding = true }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StashCard(stash = stash, pumpedTodayMl = pumpedToday) }
            if (entries.isEmpty()) {
                item { EmptyHint(stringResource(R.string.pumping_empty)) }
            }
            byDay.forEach { (day, dayEntries) ->
                item(key = "day-$day") { DayHeader(day) }
                items(dayEntries, key = { it.id }) { entry ->
                    EntryCard(
                        title = pumpingTitle(context, entry),
                        subtitle = pumpingSubtitle(context, entry, now),
                        meta = formatTime(entry.time),
                        icon = Icons.Filled.WaterDrop,
                        onDelete = { deleting = entry },
                        onClick = { editing = entry },
                        // Only milk that is still somewhere can be drunk.
                        action = if (entry.storage != MilkStorage.USED) {
                            { MarkUsedButton(onClick = { markUsed(entry) }) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** The one-tap "the baby drank it" shortcut on a stored batch's card. */
@Composable
private fun MarkUsedButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.pumping_mark_used))
    }
}

/**
 * What can be given today: the milk still in each place, with what has been
 * expressed since midnight above it. Used milk — and milk kept past its storage
 * guidance — is left out, so the numbers never flatter the stash.
 */
@Composable
private fun StashCard(stash: MilkStash, pumpedTodayMl: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pumping_stash),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.pumping_today,
                    stringResource(R.string.feeding_amount_ml, pumpedTodayMl),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (stash.isEmpty) {
                Text(
                    stringResource(R.string.pumping_stash_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = stringResource(R.string.storage_fridge),
                        value = stringResource(R.string.feeding_amount_ml, stash.fridgeMl),
                        icon = Icons.Filled.Kitchen,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = stringResource(R.string.storage_freezer),
                        value = stringResource(R.string.feeding_amount_ml, stash.freezerMl),
                        icon = Icons.Filled.AcUnit,
                        modifier = Modifier.weight(1f),
                    )
                    // Milk left out is the exception, so it only takes up room
                    // on the card while there is some.
                    if (stash.roomMl > 0) {
                        StatCard(
                            label = stringResource(R.string.storage_room),
                            value = stringResource(R.string.feeding_amount_ml, stash.roomMl),
                            icon = Icons.Filled.DeviceThermostat,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Edit (or delete) an existing session in a dialog, reusing [PumpingForm]. */
@Composable
private fun EditPumpingDialog(
    entry: PumpingEntity,
    onDismiss: () -> Unit,
    onSave: (PumpingEntity) -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.pumping_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                PumpingForm(
                    initial = entry,
                    submitLabel = stringResource(R.string.action_save),
                    onSubmit = onSave,
                    onDelete = onDelete,
                )
            }
        }
    }
}

/**
 * The shared editor used both for logging a session (no [initial]) and for
 * editing one. Holds its own field state, re-initialised whenever [initial]
 * changes; when adding, the fields reset after submitting.
 */
@Composable
private fun PumpingForm(
    initial: PumpingEntity?,
    submitLabel: String,
    onSubmit: (PumpingEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    var amount by remember(initial) { mutableStateOf(initial?.amountMl?.toString() ?: "") }
    var storage by remember(initial) { mutableStateOf(initial?.storage ?: MilkStorage.FRIDGE) }
    var side by remember(initial) { mutableStateOf(initial?.side) }
    var time by remember(initial) { mutableLongStateOf(initial?.time ?: System.currentTimeMillis()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }

    FieldLabel(stringResource(R.string.field_amount))
    NumberField(
        label = stringResource(R.string.field_amount),
        value = amount,
        onChange = { amount = it },
        suffix = stringResource(R.string.unit_ml),
    )

    FieldLabel(stringResource(R.string.field_storage))
    ChoiceChips(
        options = MilkStorage.entries,
        selected = storage,
        onSelect = { storage = it },
        labelOf = { it.label(context) },
    )
    Text(
        stringResource(R.string.pumping_guidance),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    // Optional: plenty of parents pump both sides at once and don't split it.
    FieldLabel(stringResource(R.string.field_side))
    ChoiceChips(
        options = listOf(BreastSide.LEFT, BreastSide.RIGHT, BreastSide.BOTH),
        selected = side,
        onSelect = { side = if (side == it) null else it },
        labelOf = { it.label(context) },
    )

    // A session often gets logged once the bottle is already in the fridge.
    FieldLabel(stringResource(R.string.field_date))
    DatePickerField(
        label = stringResource(R.string.picker_on),
        millis = time,
        allowFuture = false,
        onChange = { time = it },
    )

    FieldLabel(stringResource(R.string.field_time))
    TimePickerField(label = stringResource(R.string.picker_at), millis = time, onChange = { time = it })

    Spacer(Modifier.height(8.dp))
    NotesField(value = notes, onChange = { notes = it })

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (onDelete != null) Arrangement.SpaceBetween else Arrangement.End,
    ) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // An amount is the whole point of the entry, so it gates saving.
        Button(
            enabled = (amount.toIntOrNull() ?: 0) > 0,
            onClick = {
                onSubmit(
                    PumpingEntity(
                        id = initial?.id ?: 0L,
                        time = time,
                        amountMl = amount.toIntOrNull() ?: 0,
                        side = side,
                        storage = storage,
                        notes = notes.ifBlank { null },
                    ),
                )
                if (initial == null) {
                    amount = ""
                    side = null
                    notes = ""
                    time = System.currentTimeMillis()
                }
            },
        ) {
            Text(submitLabel)
        }
    }
}

/** "120 ml · Fridge" — the amount and where it went. */
private fun pumpingTitle(context: Context, entry: PumpingEntity): String = listOf(
    context.getString(R.string.feeding_amount_ml, entry.amountMl),
    entry.storage.label(context),
).joinToString(context.getString(R.string.feeding_detail_separator))

/** Side (if noted), how long it still keeps, and any notes. */
private fun pumpingSubtitle(context: Context, entry: PumpingEntity, now: Long): String {
    val parts = mutableListOf<String>()
    entry.side?.let { parts += it.label(context) }
    when {
        isPastBestBefore(entry, now) -> parts += context.getString(R.string.pumping_past_keep)
        else -> bestBefore(entry)?.let {
            parts += context.getString(R.string.pumping_keeps_until, formatDateTime(context, it))
        }
    }
    entry.notes?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(context.getString(R.string.feeding_detail_separator))
}

private fun MilkStorage.label(context: Context): String = context.getString(
    when (this) {
        MilkStorage.FRIDGE -> R.string.storage_fridge
        MilkStorage.FREEZER -> R.string.storage_freezer
        MilkStorage.ROOM -> R.string.storage_room
        MilkStorage.USED -> R.string.storage_used
    },
)

private fun BreastSide.label(context: Context): String = context.getString(
    when (this) {
        BreastSide.LEFT -> R.string.side_left
        BreastSide.RIGHT -> R.string.side_right
        BreastSide.BOTH -> R.string.side_both
    },
)
