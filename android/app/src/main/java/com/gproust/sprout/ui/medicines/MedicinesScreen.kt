package com.gproust.sprout.ui.medicines

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.MEDICINE_DAY_MS
import com.gproust.sprout.data.MedicineReadiness
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.medicineReadiness
import com.gproust.sprout.notifications.MedicineReminders
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.ConfirmDeleteDialog
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.DecimalField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SectionLabel
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.common.formatDecimal
import com.gproust.sprout.ui.common.parseDecimal
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The medicine given when it is needed, and the wait before the next one
 * (BDR-15). The calendar-shaped counterpart is the treatments screen.
 *
 * Nothing on this screen is an assessment. The colours and sentences restate
 * the intervals the parent typed in, and a dose is always loggable — the button
 * is never disabled and no dialog argues, because a dose given anyway and not
 * recorded is the outcome the whole feature exists to prevent.
 */
class MedicinesViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {

    /**
     * The medicines and every dose of them, paired.
     *
     * One state rather than two, so a recomposition cannot catch a medicine
     * alongside the previous list of doses and draw a traffic light from a
     * mixture of the two.
     */
    val state = combine(repository.medicines, repository.medicineDoses) { medicines, doses ->
        MedicinesState(medicines, doses)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MedicinesState())

    fun save(entity: MedicineEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            val saved = if (entity.id == 0L) {
                val newId = repository.addMedicine(entity) ?: return@launch
                entity.copy(id = newId)
            } else {
                repository.updateMedicine(entity)
                entity
            }
            rearm(saved)
            onDone()
        }
    }

    fun delete(entity: MedicineEntity) {
        viewModelScope.launch {
            repository.deleteMedicine(entity)
            MedicineReminders.cancel(context, entity)
        }
    }

    /** Logs a dose of [medicine] as given at [time], and moves its reminder. */
    fun give(medicine: MedicineEntity, time: Long) {
        viewModelScope.launch {
            repository.giveMedicineDose(medicine, time)
            rearm(medicine)
        }
    }

    fun updateDose(medicine: MedicineEntity?, dose: MedicineDoseEntity) {
        viewModelScope.launch {
            repository.updateMedicineDose(dose)
            medicine?.let { rearm(it) }
        }
    }

    fun deleteDose(medicine: MedicineEntity?, dose: MedicineDoseEntity) {
        viewModelScope.launch {
            repository.deleteMedicineDose(dose)
            medicine?.let { rearm(it) }
        }
    }

    /**
     * Re-reads the doses and re-arms the alarm.
     *
     * From the database rather than from the flow above: a write and the flow
     * that observes it are not ordered against each other, and arming from a
     * list that predates the dose just logged would set the alarm from the
     * previous one.
     */
    private suspend fun rearm(medicine: MedicineEntity) {
        val doses = repository.recentDosesOf(medicine.uid, MEDICINE_DAY_MS)
        MedicineReminders.schedule(context, medicine, doses)
    }
}

data class MedicinesState(
    val medicines: List<MedicineEntity> = emptyList(),
    val doses: List<MedicineDoseEntity> = emptyList(),
)

@Composable
fun MedicinesScreen(onBack: () -> Unit) {
    val vm: MedicinesViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // Asked for here rather than at launch: a parent who never opens this screen
    // is never asked about notifications at all.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    // The state is a function of the clock, so a screen drawn once and left open
    // would show "2 h 15 to wait" for as long as the parent looks at it. A minute
    // is as fine as the sentences get.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    var editing by remember { mutableStateOf<MedicineEntity?>(null) }
    var deleting by remember { mutableStateOf<MedicineEntity?>(null) }
    var editingDose by remember { mutableStateOf<MedicineDoseEntity?>(null) }

    editing?.let { initial ->
        MedicineEditor(
            initial = initial,
            onCancel = { editing = null },
            onSave = { vm.save(it) { editing = null } },
        )
        return
    }

    deleting?.let { medicine ->
        ConfirmDeleteDialog(
            onConfirm = { vm.delete(medicine); deleting = null },
            onDismiss = { deleting = null },
            title = stringResource(R.string.medicine_delete_title, medicine.name),
            body = stringResource(R.string.medicine_delete_body),
        )
    }

    editingDose?.let { dose ->
        val medicine = state.medicines.firstOrNull { it.uid == dose.medicineUid }
        DoseEditor(
            dose = dose,
            medicineName = medicine?.name.orEmpty(),
            // Null when the medicine is measured in whole doses, or has been
            // deleted out from under its history.
            unit = medicine?.doseUnit,
            onCancel = { editingDose = null },
            onSave = { vm.updateDose(medicine, it); editingDose = null },
        )
        return
    }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_medicines), onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = newMedicine() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.medicine_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.medicines.isEmpty()) {
                item { EmptyHint(stringResource(R.string.medicine_empty)) }
            }
            items(state.medicines, key = { it.id }) { medicine ->
                MedicineCard(
                    medicine = medicine,
                    readiness = medicineReadiness(medicine, state.doses, now),
                    now = now,
                    onGive = { vm.give(medicine, System.currentTimeMillis()) },
                    onEdit = { editing = medicine },
                    onDelete = { deleting = medicine },
                )
            }

            // The doses of every medicine in one list, newest first. A parent
            // checking "what has she had today" is asking across medicines, not
            // within one.
            val recent = state.doses.take(DOSE_HISTORY_LIMIT)
            if (recent.isNotEmpty()) {
                item(key = "header-history") {
                    SectionLabel(stringResource(R.string.medicine_history))
                }
                items(recent, key = { "dose-${it.id}" }) { dose ->
                    val medicine = state.medicines.firstOrNull { it.uid == dose.medicineUid }
                    val given = amountLabel(context, dose.amount, medicine?.doseUnit)
                    EntryCard(
                        title = listOfNotNull(
                            medicine?.name ?: stringResource(R.string.medicine_never_given),
                            given,
                        ).joinToString(stringResource(R.string.feeding_detail_separator)),
                        subtitle = formatDateTime(context, dose.time),
                        meta = dose.notes.orEmpty(),
                        icon = Icons.Filled.Medication,
                        onClick = { editingDose = dose },
                        onDelete = { vm.deleteDose(medicine, dose) },
                    )
                }
            }
        }
    }
}

/** How many doses the history shows before it stops being a history and becomes a scroll. */
private const val DOSE_HISTORY_LIMIT = 50

/**
 * One medicine, with where it stands right now.
 *
 * The state is drawn three ways at once — a coloured dot, its own icon, and a
 * sentence — because red/amber/green is exactly the palette a deuteranope reads
 * worst, and this is not a chart that can be studied at leisure (BDR-15). The
 * sentence is what the card leads with; the colour agrees with it.
 */
@Composable
private fun MedicineCard(
    medicine: MedicineEntity,
    readiness: MedicineReadiness,
    now: Long,
    onGive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                    val title = if (medicine.dose.isNullOrBlank()) {
                        medicine.name
                    } else {
                        stringResource(R.string.treatment_title_dose, medicine.name, medicine.dose)
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        intervalSummary(context, medicine),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(levelColor(readiness.level), CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    levelIcon(readiness.level),
                    // Decorative: the sentence beside it says the same thing,
                    // and a screen reader announcing both would say it twice.
                    contentDescription = null,
                    tint = levelColor(readiness.level),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stateSentence(context, readiness, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = levelColor(readiness.level),
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                lastDoseLine(context, readiness),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )

            Spacer(Modifier.height(8.dp))
            // Never disabled, whatever the light says. Sprout records what
            // happened; a prescriber may well have said otherwise, and a dose
            // given but not logged is the failure this screen exists to prevent.
            FilledTonalButton(onClick = onGive, modifier = Modifier.padding(end = 12.dp)) {
                Text(stringResource(R.string.medicine_give))
            }
        }
    }
}

/**
 * The medicine a new one starts as.
 *
 * Paracetamol at six to eight hours, because it is the medicine nearly everyone
 * opens this screen for — and every field is editable, sits under the line
 * saying the numbers come from a prescriber or a leaflet, and is nothing Sprout
 * is asserting about any child (BDR-15).
 */
internal fun newMedicine() = MedicineEntity(
    name = "",
    minIntervalMinutes = 6 * 60,
    comfortIntervalMinutes = 8 * 60,
)

@Composable
private fun MedicineEditor(
    initial: MedicineEntity,
    onCancel: () -> Unit,
    onSave: (MedicineEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var dose by remember { mutableStateOf(initial.dose.orEmpty()) }
    var minHours by remember { mutableStateOf(hoursText(initial.minIntervalMinutes)) }
    var doseAmount by remember {
        mutableStateOf(initial.doseAmount?.let { formatDecimal(it) }.orEmpty())
    }
    var unit by remember { mutableStateOf(initial.doseUnit.orEmpty()) }
    var maxAmount by remember {
        mutableStateOf(initial.maxAmountPerDay?.let { formatDecimal(it) }.orEmpty())
    }
    var comfortHours by remember {
        mutableStateOf(initial.comfortIntervalMinutes?.let { hoursText(it) }.orEmpty())
    }
    var maxPerDay by remember { mutableStateOf(initial.maxPerDay?.toString().orEmpty()) }
    var remind by remember { mutableStateOf(initial.remindWhenDue) }
    var remindAtComfort by remember { mutableStateOf(initial.remindAtComfort) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    val isNew = initial.id == 0L

    Scaffold(
        topBar = {
            SproutTopBar(
                stringResource(if (isNew) R.string.medicine_new else R.string.medicine_edit),
                onBack = onCancel,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.medicine_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it },
                label = { Text(stringResource(R.string.medicine_dose)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FieldLabel(stringResource(R.string.medicine_intervals))
            // Above the fields, not below them: it is the sentence that decides
            // what a parent types into them.
            Text(
                stringResource(R.string.medicine_intervals_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = stringResource(R.string.medicine_min_interval),
                    value = minHours,
                    onChange = { minHours = it },
                    suffix = stringResource(R.string.medicine_hours_suffix),
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = stringResource(R.string.medicine_comfort_interval),
                    value = comfortHours,
                    onChange = { comfortHours = it },
                    suffix = stringResource(R.string.medicine_hours_suffix),
                    modifier = Modifier.weight(1f),
                )
            }
            // Said here rather than only in the hint above, because a blank
            // wait is a deliberate answer — "the leaflet gave no gap" — and a
            // parent who does not know that invents six hours instead.
            Text(
                stringResource(R.string.medicine_no_gap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField(
                label = stringResource(R.string.medicine_max_per_day),
                value = maxPerDay,
                onChange = { maxPerDay = it },
                suffix = stringResource(R.string.medicine_doses_suffix),
            )

            FieldLabel(stringResource(R.string.medicine_amounts))
            Text(
                stringResource(R.string.medicine_amounts_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DecimalField(
                    label = stringResource(R.string.medicine_dose_amount),
                    value = doseAmount,
                    onChange = { doseAmount = it },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text(stringResource(R.string.medicine_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            DecimalField(
                label = stringResource(R.string.medicine_max_amount_per_day),
                value = maxAmount,
                onChange = { maxAmount = it },
                suffix = unit.trim().ifBlank { null },
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.medicine_remind), Modifier.weight(1f))
                Switch(checked = remind, onCheckedChange = { remind = it })
            }
            // The second boundary only exists when there are two, so the choice
            // only appears then.
            if (remind && comfortHours.isNotBlank()) {
                FieldLabel(stringResource(R.string.medicine_remind_at))
                // Resolved outside the chips: `labelOf` is a plain function, so
                // `stringResource` cannot be called from inside it.
                val atMinLabel = stringResource(R.string.medicine_remind_at_min)
                val atComfortLabel = stringResource(R.string.medicine_remind_at_comfort)
                ChoiceChips(
                    options = listOf(false, true),
                    selected = remindAtComfort,
                    onSelect = { remindAtComfort = it },
                    labelOf = { atComfort -> if (atComfort) atComfortLabel else atMinLabel },
                )
            }

            NotesField(notes, { notes = it })

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        // Blank is zero, not an hour: a medicine whose leaflet
                        // gave no gap is held by its daily ceilings alone
                        // (BDR-18). Only a typed number becomes a wait.
                        val min = minHours.toIntOrNull()?.takeIf { it > 0 } ?: 0
                        val typedUnit = unit.trim().ifBlank { null }
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                dose = dose.trim().ifBlank { null },
                                minIntervalMinutes = min * 60,
                                comfortIntervalMinutes = comfortHours.toIntOrNull()
                                    ?.takeIf { it > 0 }?.times(60),
                                maxPerDay = maxPerDay.toIntOrNull()?.takeIf { it > 0 },
                                doseAmount = parseDecimal(doseAmount)?.takeIf { it > 0 },
                                doseUnit = typedUnit,
                                maxAmountPerDay = parseDecimal(maxAmount)?.takeIf { it > 0 },
                                remindWhenDue = remind,
                                remindAtComfort = remindAtComfort,
                                notes = notes.trim().ifBlank { null },
                            ),
                        )
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * Correcting a dose — the time it was given, and a note.
 *
 * Worth its own screen because the common correction is a real one: the dose was
 * given at 2 a.m. and logged at 6, and the traffic light is wrong by four hours
 * until someone can say so.
 */
@Composable
private fun DoseEditor(
    dose: MedicineDoseEntity,
    medicineName: String,
    unit: String?,
    onCancel: () -> Unit,
    onSave: (MedicineDoseEntity) -> Unit,
) {
    var time by remember { mutableLongStateOf(dose.time) }
    var amount by remember { mutableStateOf(dose.amount?.let { formatDecimal(it) }.orEmpty()) }
    var notes by remember { mutableStateOf(dose.notes.orEmpty()) }
    Scaffold(
        topBar = {
            SproutTopBar(stringResource(R.string.medicine_dose_edit), onBack = onCancel)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.medicine_of, medicineName),
                style = MaterialTheme.typography.titleMedium,
            )
            DatePickerField(
                label = stringResource(R.string.medicine_dose_time),
                millis = time,
                onChange = { time = it },
                // A dose is given now or was given earlier; there is no logging
                // one for tomorrow.
                allowFuture = false,
            )
            TimePickerField(
                label = stringResource(R.string.medicine_dose_time),
                millis = time,
                onChange = { time = it },
            )
            // Only for a medicine that is measured in something: a paracetamol
            // dose is one dose, and a field asking how much of it would be a
            // question with no answer.
            if (unit != null) {
                DecimalField(
                    label = stringResource(R.string.medicine_dose_amount_given),
                    value = amount,
                    onChange = { amount = it },
                    suffix = unit.ifBlank { null },
                )
            }
            NotesField(notes, { notes = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        onSave(
                            dose.copy(
                                time = time,
                                // Cleared rather than kept at zero when the
                                // field is emptied: a dose that says nothing
                                // about quantity adds nothing to the day, which
                                // is not the same as a dose of none.
                                amount = if (unit == null) dose.amount else parseDecimal(amount),
                                notes = notes.trim().ifBlank { null },
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * Minutes as whole hours for the editor's fields.
 *
 * The fields are in hours because that is the unit every leaflet uses, and a
 * medicine whose stored interval is not a whole number of hours — which nothing
 * in the app can currently produce — rounds down rather than showing a blank.
 *
 * Zero comes back **blank**, and that is the whole of how a medicine with no
 * gap rule is expressed: an empty field saves as no wait, and a saved no-wait
 * reopens empty (BDR-18).
 */
private fun hoursText(minutes: Int): String =
    if (minutes <= 0) "" else (minutes / 60).coerceAtLeast(1).toString()
