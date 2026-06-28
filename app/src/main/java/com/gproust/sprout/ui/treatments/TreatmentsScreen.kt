@file:OptIn(ExperimentalLayoutApi::class)

package com.gproust.sprout.ui.treatments

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.notifications.TreatmentReminders
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TreatmentsViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {
    val treatments = repository.treatments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(entity: TreatmentEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            val saved = if (entity.id == 0L) {
                val newId = repository.addTreatment(entity) ?: return@launch
                entity.copy(id = newId)
            } else {
                repository.updateTreatment(entity)
                entity
            }
            TreatmentReminders.schedule(context, saved)
            onDone()
        }
    }

    fun delete(entity: TreatmentEntity) {
        viewModelScope.launch {
            repository.deleteTreatment(entity)
            TreatmentReminders.cancel(context, entity)
        }
    }
}

@Composable
fun TreatmentsScreen(onBack: () -> Unit) {
    val vm: TreatmentsViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val treatments by vm.treatments.collectAsState()
    val context = LocalContext.current

    // Ask for notification permission up front so reminders can actually post (Android 13+).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var editing by remember { mutableStateOf<TreatmentEntity?>(null) }

    editing?.let { initial ->
        TreatmentEditor(
            initial = initial,
            onCancel = { editing = null },
            onSave = { vm.save(it) { editing = null } },
        )
        return
    }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_treatments), onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = newTreatment() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.treatment_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (treatments.isEmpty()) {
                item { EmptyHint(stringResource(R.string.treatment_empty)) }
            }
            items(treatments, key = { it.id }) { treatment ->
                TreatmentCard(
                    treatment = treatment,
                    summary = scheduleSummary(context, treatment),
                    onEdit = { editing = treatment },
                    onDelete = { vm.delete(treatment) },
                )
            }
        }
    }
}

@Composable
private fun TreatmentCard(
    treatment: TreatmentEntity,
    summary: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Medication,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit),
            ) {
                val title = if (treatment.dose.isNullOrBlank()) {
                    treatment.name
                } else {
                    stringResource(R.string.treatment_title_dose, treatment.name, treatment.dose)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!treatment.remindersEnabled) {
                    Text(
                        stringResource(R.string.treatment_reminders_off),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun TreatmentEditor(
    initial: TreatmentEntity,
    onCancel: () -> Unit,
    onSave: (TreatmentEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var dose by remember { mutableStateOf(initial.dose.orEmpty()) }
    var mode by remember { mutableStateOf(FreqMode.from(initial.intervalDays)) }
    var customDays by remember {
        mutableStateOf(if (mode == FreqMode.EVERY_N) initial.intervalDays.toString() else "2")
    }
    val times = remember { initial.timesOfDay.ifEmpty { listOf(9 * 60) }.toMutableStateList() }
    var startDate by remember { mutableStateOf(initial.startDate) }
    var hasEnd by remember { mutableStateOf(initial.endDate != null) }
    var endDate by remember { mutableStateOf(initial.endDate ?: plusOneYear(initial.startDate)) }
    var reminders by remember { mutableStateOf(initial.remindersEnabled) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    val context = LocalContext.current

    val isNew = initial.id == 0L
    Scaffold(
        topBar = {
            SproutTopBar(
                stringResource(if (isNew) R.string.treatment_new else R.string.treatment_edit),
                onBack = onCancel,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.treatment_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it },
                label = { Text(stringResource(R.string.treatment_dose)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FieldLabel(stringResource(R.string.treatment_frequency))
            ChoiceChips(
                options = FreqMode.entries,
                selected = mode,
                onSelect = { mode = it },
                labelOf = { context.getString(it.labelRes) },
            )
            if (mode == FreqMode.EVERY_N) {
                NumberField(
                    label = stringResource(R.string.treatment_every_n_days),
                    value = customDays,
                    onChange = { customDays = it },
                    suffix = stringResource(R.string.treatment_days_suffix),
                )
            }

            FieldLabel(stringResource(R.string.treatment_times))
            times.forEachIndexed { index, minute ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        TimePickerField(
                            label = stringResource(R.string.picker_at),
                            millis = minuteToMillis(minute),
                            onChange = { times[index] = millisToMinute(it) },
                        )
                    }
                    if (times.size > 1) {
                        IconButton(onClick = { times.removeAt(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.cd_delete),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { times.add(12 * 60) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.treatment_add_time))
            }

            FieldLabel(stringResource(R.string.treatment_start))
            DatePickerField(
                label = stringResource(R.string.picker_on),
                millis = startDate,
                onChange = { startDate = it },
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.treatment_has_end))
                Switch(checked = hasEnd, onCheckedChange = { hasEnd = it })
            }
            if (hasEnd) {
                DatePickerField(
                    label = stringResource(R.string.treatment_end),
                    millis = endDate,
                    onChange = { endDate = it },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.treatment_remind_me))
                Switch(checked = reminders, onCheckedChange = { reminders = it })
            }

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = name.isNotBlank() && times.isNotEmpty(),
                onClick = {
                    val interval = when (mode) {
                        FreqMode.DAILY -> 1
                        FreqMode.WEEKLY -> 7
                        FreqMode.EVERY_N -> customDays.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    }
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            dose = dose.trim().ifBlank { null },
                            intervalDays = interval,
                            timesOfDay = times.sorted(),
                            startDate = startDate,
                            endDate = if (hasEnd) endDate else null,
                            remindersEnabled = reminders,
                            active = true,
                            notes = notes.trim().ifBlank { null },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

/** Frequency presets; EVERY_N reveals a day-count field. */
private enum class FreqMode(val labelRes: Int) {
    DAILY(R.string.treatment_freq_daily),
    EVERY_N(R.string.treatment_freq_every_n_label),
    WEEKLY(R.string.treatment_freq_weekly);

    companion object {
        fun from(intervalDays: Int): FreqMode = when (intervalDays) {
            1 -> DAILY
            7 -> WEEKLY
            else -> EVERY_N
        }
    }
}

private fun newTreatment(): TreatmentEntity =
    TreatmentEntity(name = "", startDate = System.currentTimeMillis(), timesOfDay = listOf(9 * 60))

private fun scheduleSummary(context: Context, t: TreatmentEntity): String {
    val every = when (t.intervalDays) {
        1 -> context.getString(R.string.treatment_freq_daily)
        7 -> context.getString(R.string.treatment_freq_weekly)
        else -> context.getString(R.string.treatment_freq_every_n, t.intervalDays)
    }
    val times = t.timesOfDay.sorted().joinToString(", ") { formatMinute(it) }
    return context.getString(R.string.treatment_schedule_summary, every, times)
}

private fun formatMinute(minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

private fun minuteToMillis(minute: Int): Long {
    val zone = ZoneId.systemDefault()
    return Instant.now().atZone(zone).toLocalDate()
        .atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
}

private fun millisToMinute(millis: Long): Int {
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return t.hour * 60 + t.minute
}

private fun plusOneYear(startMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(startMillis).atZone(zone).plusYears(1).toInstant().toEpochMilli()
}
