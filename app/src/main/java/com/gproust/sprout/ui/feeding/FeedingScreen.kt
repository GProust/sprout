package com.gproust.sprout.ui.feeding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatClock
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A breastfeeding session being timed live but not yet saved. [segments] holds
 * the *completed* back-and-forth stretches; the breast currently nursing runs
 * from [segmentStart] until the next switch or stop, ticking off the clock.
 */
data class NursingSession(
    val sessionStart: Long,
    val currentSide: BreastSide,
    val segmentStart: Long,
    val segments: List<NursingSegment> = emptyList(),
)

class FeedingViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {
    val feedings = repository.feedings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _nursing = MutableStateFlow<NursingSession?>(null)
    val nursing: StateFlow<NursingSession?> = _nursing.asStateFlow()

    fun add(entity: FeedingEntity) = viewModelScope.launch {
        repository.addFeeding(entity)
        // A feed was logged → push the "too long since a feed" reminder out.
        FeedingReminders.rescheduleActiveBaby(context, repository)
    }

    fun delete(entity: FeedingEntity) = viewModelScope.launch {
        repository.deleteFeeding(entity)
        FeedingReminders.rescheduleActiveBaby(context, repository)
    }

    /** Begin timing a breastfeeding session on [side] (left or right). */
    fun startNursing(side: BreastSide) {
        val now = System.currentTimeMillis()
        _nursing.value = NursingSession(sessionStart = now, currentSide = side, segmentStart = now)
    }

    /** Bank the current breast as a completed segment and switch to the other. */
    fun switchBreast() {
        val s = _nursing.value ?: return
        val now = System.currentTimeMillis()
        val completed = NursingSegment(s.currentSide, s.segmentStart, now)
        val next = if (s.currentSide == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT
        _nursing.value = s.copy(
            currentSide = next,
            segmentStart = now,
            segments = s.segments + completed,
        )
    }

    /** Finish the session, persist it as a feeding, and clear the timer. */
    fun stopNursing(notes: String = "") {
        val s = _nursing.value ?: return
        val now = System.currentTimeMillis()
        val all = s.segments + NursingSegment(s.currentSide, s.segmentStart, now)
        val leftMs = all.filter { it.side == BreastSide.LEFT }.sumOf { it.endTime - it.startTime }
        val rightMs = all.filter { it.side == BreastSide.RIGHT }.sumOf { it.endTime - it.startTime }
        val side = when {
            leftMs > 0 && rightMs > 0 -> BreastSide.BOTH
            rightMs > 0 -> BreastSide.RIGHT
            else -> BreastSide.LEFT
        }
        // babyId is stamped by the repository on insert.
        add(
            FeedingEntity(
                type = FeedType.BREAST,
                side = side,
                startTime = s.sessionStart,
                endTime = now,
                leftDurationMs = leftMs.takeIf { it > 0 },
                rightDurationMs = rightMs.takeIf { it > 0 },
                segments = all,
                notes = notes.ifBlank { null },
            ),
        )
        _nursing.value = null
    }

    /** Discard the running session without saving it. */
    fun cancelNursing() {
        _nursing.value = null
    }
}

@Composable
fun FeedingScreen(
    onOpenNursing: (BreastSide) -> Unit = {},
    vm: FeedingViewModel = viewModel(factory = rememberSproutViewModelFactory()),
) {
    val feedings by vm.feedings.collectAsState()
    val nursing by vm.nursing.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<FeedingEntity?>(null) }

    editing?.let { entry ->
        EditFeedingDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { vm.add(it); editing = null },
            onDelete = { vm.delete(entry); editing = null },
        )
    }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_feeding)) },
        bottomBar = {
            NursingBar(
                session = nursing,
                onStart = onOpenNursing,
                onResume = { nursing?.let { onOpenNursing(it.currentSide) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ManualFeedCard(onAdd = vm::add) }
            item {
                Text(
                    stringResource(R.string.history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (feedings.isEmpty()) {
                item { EmptyHint(stringResource(R.string.feeding_empty)) }
            }
            items(feedings, key = { it.id }) { entry ->
                EntryCard(
                    title = feedingTitle(context, entry),
                    subtitle = feedingSubtitle(context, entry),
                    meta = formatTime(entry.startTime),
                    icon = Icons.Filled.LocalDrink,
                    onDelete = { vm.delete(entry) },
                    onClick = { editing = entry },
                )
            }
        }
    }
}

/**
 * The breastfeeding start/stop controls, pinned to the bottom of the feeding
 * screen. Idle: a Left and a Right button (positioned to match the breast).
 * Running: a single button that re-opens the live timer, showing elapsed time
 * so a session left in the background is never forgotten.
 */
@Composable
private fun NursingBar(
    session: NursingSession?,
    onStart: (BreastSide) -> Unit,
    onResume: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.feeding_breastfeeding),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            if (session == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onStart(BreastSide.LEFT) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feeding_start_left))
                    }
                    Button(onClick = { onStart(BreastSide.RIGHT) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feeding_start_right))
                    }
                }
            } else {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(session.sessionStart) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val completed = session.segments.sumOf { it.endTime - it.startTime }
                val totalMs = (completed + (now - session.segmentStart)).coerceAtLeast(0L)
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feeding_resume, formatClock(totalMs)))
                }
            }
        }
    }
}

/**
 * Full-screen live breastfeeding timer. Started from the feeding screen's
 * Left/Right buttons; lets you switch sides (banking each segment's duration)
 * and stop to save, or cancel to discard. Leaving via Back keeps the session
 * running so it can be resumed.
 */
@Composable
fun NursingScreen(
    side: BreastSide,
    onDone: () -> Unit = {},
    vm: FeedingViewModel = viewModel(factory = rememberSproutViewModelFactory()),
) {
    val session by vm.nursing.collectAsState()

    // Start a session on first entry (or after process death lost the in-memory
    // one). An already-running session — e.g. resumed from the bar — is kept.
    LaunchedEffect(Unit) {
        if (vm.nursing.value == null) vm.startNursing(side)
    }

    Scaffold(
        topBar = {
            SproutTopBar(stringResource(R.string.feeding_breastfeeding), onBack = onDone)
        },
    ) { padding ->
        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            NursingRunning(
                session = s,
                onSwitch = vm::switchBreast,
                onStop = { notes -> vm.stopNursing(notes); onDone() },
                onCancel = { vm.cancelNursing(); onDone() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun NursingRunning(
    session: NursingSession,
    onSwitch: () -> Unit,
    onStop: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    // Tick once a second so the live timer keeps moving while this is shown.
    LaunchedEffect(session.sessionStart) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val currentSegment = (now - session.segmentStart).coerceAtLeast(0L)
    val onLeft = session.currentSide == BreastSide.LEFT
    val completedLeft = session.segments.filter { it.side == BreastSide.LEFT }.sumOf { it.endTime - it.startTime }
    val completedRight = session.segments.filter { it.side == BreastSide.RIGHT }.sumOf { it.endTime - it.startTime }
    val leftMs = completedLeft + if (onLeft) currentSegment else 0L
    val rightMs = completedRight + if (!onLeft) currentSegment else 0L
    val totalMs = leftMs + rightMs
    // Every stretch so far, including the one in progress (ends at `now`).
    val liveSegments = session.segments + NursingSegment(session.currentSide, session.segmentStart, now)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            formatClock(totalMs),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(if (onLeft) R.string.feeding_on_left else R.string.feeding_on_right),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SideTotal(label = stringResource(R.string.side_left), value = formatClock(leftMs), active = onLeft)
            SideTotal(label = stringResource(R.string.side_right), value = formatClock(rightMs), active = !onLeft)
        }

        // Each back-and-forth stretch with its own time range and duration.
        Spacer(Modifier.height(20.dp))
        FieldLabel(stringResource(R.string.feeding_segments))
        liveSegments.forEachIndexed { i, seg ->
            SegmentRow(
                side = seg.side.label(context),
                range = segmentRange(seg),
                duration = formatDuration(context, (seg.endTime - seg.startTime).coerceAtLeast(0L)),
                active = i == liveSegments.lastIndex,
            )
        }

        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (onLeft) R.string.feeding_switch_to_right else R.string.feeding_switch_to_left))
        }

        Spacer(Modifier.height(12.dp))
        NotesField(value = notes, onChange = { notes = it })

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel is a small round red cross; saving is the prominent action.
            OutlinedIconButton(
                onClick = onCancel,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Button(onClick = { onStop(notes) }, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.feeding_stop_save))
            }
        }
    }
}

@Composable
private fun SegmentRow(side: String, range: String, duration: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            side,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            range,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            duration,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
    }
}

@Composable
private fun SideTotal(label: String, value: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Manual entry for any feed — including a past breastfeed — without using the
 * live timer. A breastfeed can record its length as an end time or a duration;
 * bottle and solids just need a time.
 */
@Composable
private fun ManualFeedCard(onAdd: (FeedingEntity) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.feeding_log_title), style = MaterialTheme.typography.titleMedium)
            FeedingForm(
                initial = null,
                submitLabel = stringResource(R.string.feeding_add),
                onSubmit = onAdd,
            )
        }
    }
}

/** Edit (or delete) an existing feed in a dialog, reusing [FeedingForm]. */
@Composable
private fun EditFeedingDialog(
    entry: FeedingEntity,
    onDismiss: () -> Unit,
    onSave: (FeedingEntity) -> Unit,
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
                    stringResource(R.string.feeding_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                FeedingForm(
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
 * The shared feed editor used both for manual logging (no [initial]) and for
 * editing an existing entry. Holds its own field state, re-initialised whenever
 * [initial] changes. When adding, the fields reset after submitting.
 */
@Composable
private fun FeedingForm(
    initial: FeedingEntity?,
    submitLabel: String,
    onSubmit: (FeedingEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()

    // The recorded length of an existing breastfeed: the summed segments, else
    // the end time, else (older entries) the sum of the per-side durations.
    val initialLengthMs = initial?.let { e ->
        e.segments.takeIf { it.isNotEmpty() }?.sumOf { it.endTime - it.startTime }
            ?: e.endTime?.let { (it - e.startTime).takeIf { d -> d > 0 } }
            ?: ((e.leftDurationMs ?: 0L) + (e.rightDurationMs ?: 0L)).takeIf { it > 0 }
    }

    var type by remember(initial) { mutableStateOf(initial?.type ?: FeedType.BREAST) }
    var side by remember(initial) { mutableStateOf(initial?.side ?: BreastSide.LEFT) }
    var amount by remember(initial) { mutableStateOf(initial?.amountMl?.toString() ?: "") }
    var start by remember(initial) { mutableLongStateOf(initial?.startTime ?: now) }
    // Whether the breastfeed's length is entered as an end time or a duration.
    var byEnd by remember(initial) { mutableStateOf(initial?.endTime != null) }
    var end by remember(initial) {
        mutableLongStateOf(initial?.endTime ?: (initial?.startTime ?: now))
    }
    var durationMin by remember(initial) {
        mutableStateOf(initialLengthMs?.let { (it / 60_000L).toString() } ?: "")
    }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }

    fun reset() {
        type = FeedType.BREAST
        side = BreastSide.LEFT
        amount = ""
        start = System.currentTimeMillis()
        byEnd = false
        end = System.currentTimeMillis()
        durationMin = ""
        notes = ""
    }

    fun build(): FeedingEntity {
        // Length only applies to breastfeeds; from an end time or a duration.
        val lengthMs = when {
            type != FeedType.BREAST -> null
            byEnd -> (end - start).takeIf { it > 0 }
            else -> durationMin.toLongOrNull()?.times(60_000L)?.takeIf { it > 0 }
        }
        val breast = type == FeedType.BREAST
        // A single-sided manual length becomes one timed segment; a both-sides
        // length can't be split here, so it's kept only as the total (end time).
        val segments = if (breast && lengthMs != null && side != BreastSide.BOTH) {
            listOf(NursingSegment(side, start, start + lengthMs))
        } else {
            emptyList()
        }
        return FeedingEntity(
            id = initial?.id ?: 0L,
            type = type,
            side = if (breast) side else null,
            amountMl = if (type == FeedType.BOTTLE) amount.toIntOrNull() else null,
            startTime = start,
            endTime = if (breast) lengthMs?.let { start + it } else null,
            leftDurationMs = if (breast && side == BreastSide.LEFT) lengthMs else null,
            rightDurationMs = if (breast && side == BreastSide.RIGHT) lengthMs else null,
            segments = segments,
            notes = notes.ifBlank { null },
        )
    }

    FieldLabel(stringResource(R.string.field_type))
    ChoiceChips(
        options = FeedType.entries,
        selected = type,
        onSelect = { type = it },
        labelOf = { it.label(context) },
    )

    when (type) {
        FeedType.BREAST -> {
            FieldLabel(stringResource(R.string.field_side))
            ChoiceChips(
                options = BreastSide.entries,
                selected = side,
                onSelect = { side = it },
                labelOf = { it.label(context) },
            )
        }
        FeedType.BOTTLE -> {
            FieldLabel(stringResource(R.string.field_amount))
            NumberField(
                label = stringResource(R.string.field_amount),
                value = amount,
                onChange = { amount = it },
                suffix = stringResource(R.string.unit_ml),
            )
        }
        FeedType.SOLID -> Unit
    }

    FieldLabel(stringResource(R.string.field_time))
    TimePickerField(label = stringResource(R.string.picker_at), millis = start, onChange = { start = it })

    if (type == FeedType.BREAST) {
        val durationLabel = stringResource(R.string.field_duration)
        val endLabel = stringResource(R.string.feeding_length_end)
        FieldLabel(stringResource(R.string.feeding_length))
        ChoiceChips(
            options = listOf(false, true),
            selected = byEnd,
            onSelect = { byEnd = it },
            labelOf = { if (it) endLabel else durationLabel },
        )
        Spacer(Modifier.height(4.dp))
        if (byEnd) {
            TimePickerField(label = stringResource(R.string.picker_to), millis = end, onChange = { end = it })
        } else {
            NumberField(
                label = stringResource(R.string.feeding_duration_minutes),
                value = durationMin,
                onChange = { durationMin = it },
                suffix = stringResource(R.string.unit_min),
            )
        }
    }

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
        Button(onClick = {
            onSubmit(build())
            if (initial == null) reset()
        }) {
            Text(submitLabel)
        }
    }
}

private fun feedingTitle(context: Context, entry: FeedingEntity): String {
    val sep = context.getString(R.string.feeding_detail_separator)
    return when (entry.type) {
        FeedType.BREAST -> context.getString(R.string.feed_type_breast) +
            (entry.side?.let { sep + it.label(context) } ?: "")
        FeedType.BOTTLE -> context.getString(R.string.feed_type_bottle) +
            (entry.amountMl?.let { sep + context.getString(R.string.feeding_amount_ml, it) } ?: "")
        FeedType.SOLID -> context.getString(R.string.feed_type_solid)
    }
}

/** "10:00–10:08" — the clock range a segment covers. */
private fun segmentRange(seg: NursingSegment): String =
    "${formatTime(seg.startTime)}–${formatTime(seg.endTime)}"

/** "Left 8m · 10:00–10:08" for one nursing segment. */
private fun segmentLine(context: Context, seg: NursingSegment): String = context.getString(
    R.string.feeding_segment_line,
    seg.side.label(context),
    formatDuration(context, (seg.endTime - seg.startTime).coerceAtLeast(0L)),
    segmentRange(seg),
)

private fun feedingSubtitle(context: Context, entry: FeedingEntity): String {
    // A timed breastfeed lists each back-and-forth stretch, one per line.
    if (entry.type == FeedType.BREAST && entry.segments.isNotEmpty()) {
        val lines = entry.segments.map { segmentLine(context, it) }.toMutableList()
        entry.notes?.takeIf { it.isNotBlank() }?.let { lines += it }
        return lines.joinToString("\n")
    }
    val sep = context.getString(R.string.feeding_detail_separator)
    val parts = mutableListOf<String>()
    if (entry.type == FeedType.BREAST) {
        val hasPerSide = entry.leftDurationMs != null || entry.rightDurationMs != null
        if (hasPerSide) {
            entry.leftDurationMs?.let {
                parts += context.getString(
                    R.string.feeding_side_time,
                    context.getString(R.string.side_left),
                    formatDuration(context, it),
                )
            }
            entry.rightDurationMs?.let {
                parts += context.getString(
                    R.string.feeding_side_time,
                    context.getString(R.string.side_right),
                    formatDuration(context, it),
                )
            }
        } else {
            // A both-sides or quick breastfeed: show the total length only.
            entry.endTime?.takeIf { it > entry.startTime }?.let {
                parts += formatDuration(context, it - entry.startTime)
            }
        }
    }
    entry.notes?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(sep)
}

private fun BreastSide.label(context: Context): String = context.getString(
    when (this) {
        BreastSide.LEFT -> R.string.side_left
        BreastSide.RIGHT -> R.string.side_right
        BreastSide.BOTH -> R.string.side_both
    },
)

private fun FeedType.label(context: Context): String = context.getString(
    when (this) {
        FeedType.BREAST -> R.string.feed_type_breast
        FeedType.BOTTLE -> R.string.feed_type_bottle
        FeedType.SOLID -> R.string.feed_type_solid
    },
)
