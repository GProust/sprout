package com.gproust.sprout.ui.feeding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
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
 * A breastfeeding session being timed live but not yet saved. The accumulated
 * [leftMs] / [rightMs] hold only *completed* segments; the time on the breast
 * currently nursing is derived from [segmentStart] as the clock ticks.
 */
data class NursingSession(
    val sessionStart: Long,
    val currentSide: BreastSide,
    val segmentStart: Long,
    val leftMs: Long = 0L,
    val rightMs: Long = 0L,
    /** Epoch millis of the most recent side switch, if any. */
    val lastSwitch: Long? = null,
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

    /** Bank the time on the current breast and switch to the other one. */
    fun switchBreast() {
        val s = _nursing.value ?: return
        val now = System.currentTimeMillis()
        val segment = now - s.segmentStart
        _nursing.value = if (s.currentSide == BreastSide.LEFT) {
            s.copy(
                currentSide = BreastSide.RIGHT,
                segmentStart = now,
                leftMs = s.leftMs + segment,
                lastSwitch = now,
            )
        } else {
            s.copy(
                currentSide = BreastSide.LEFT,
                segmentStart = now,
                rightMs = s.rightMs + segment,
                lastSwitch = now,
            )
        }
    }

    /** Finish the session, persist it as a feeding, and clear the timer. */
    fun stopNursing(notes: String = "") {
        val s = _nursing.value ?: return
        val now = System.currentTimeMillis()
        val segment = now - s.segmentStart
        val leftMs = s.leftMs + if (s.currentSide == BreastSide.LEFT) segment else 0L
        val rightMs = s.rightMs + if (s.currentSide == BreastSide.RIGHT) segment else 0L
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
fun FeedingScreen() {
    val vm: FeedingViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val feedings by vm.feedings.collectAsState()
    val nursing by vm.nursing.collectAsState()
    val context = LocalContext.current

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_feeding)) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NursingCard(
                    session = nursing,
                    onStart = vm::startNursing,
                    onSwitch = vm::switchBreast,
                    onStop = vm::stopNursing,
                    onCancel = vm::cancelNursing,
                )
            }
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
                )
            }
        }
    }
}

@Composable
private fun NursingCard(
    session: NursingSession?,
    onStart: (BreastSide) -> Unit,
    onSwitch: () -> Unit,
    onStop: (String) -> Unit,
    onCancel: () -> Unit,
) {
    ElevatedCard {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.feeding_breastfeeding), style = MaterialTheme.typography.titleMedium)
            if (session == null) {
                NursingIdle(onStart = onStart)
            } else {
                NursingRunning(session = session, onSwitch = onSwitch, onStop = onStop, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun NursingIdle(onStart: (BreastSide) -> Unit) {
    Text(
        stringResource(R.string.feeding_nursing_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onStart(BreastSide.LEFT) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.feeding_start_left))
        }
        Button(onClick = { onStart(BreastSide.RIGHT) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.feeding_start_right))
        }
    }
}

@Composable
private fun NursingRunning(
    session: NursingSession,
    onSwitch: () -> Unit,
    onStop: (String) -> Unit,
    onCancel: () -> Unit,
) {
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
    val leftMs = session.leftMs + if (session.currentSide == BreastSide.LEFT) currentSegment else 0L
    val rightMs = session.rightMs + if (session.currentSide == BreastSide.RIGHT) currentSegment else 0L
    val totalMs = leftMs + rightMs
    val onLeft = session.currentSide == BreastSide.LEFT

    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatClock(totalMs),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(if (onLeft) R.string.feeding_on_left else R.string.feeding_on_right),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SideTotal(label = stringResource(R.string.side_left), value = formatClock(leftMs), active = onLeft)
        SideTotal(label = stringResource(R.string.side_right), value = formatClock(rightMs), active = !onLeft)
    }
    session.lastSwitch?.let {
        Text(
            stringResource(R.string.feeding_last_switch, formatTime(it)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Spacer(Modifier.height(12.dp))
    FilledTonalButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (onLeft) R.string.feeding_switch_to_right else R.string.feeding_switch_to_left))
    }

    Spacer(Modifier.height(8.dp))
    NotesField(value = notes, onChange = { notes = it })

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(onClick = { onStop(notes) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.feeding_stop_save))
        }
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
 * live timer. Useful for logging a feed after the fact, or a quick bottle/solid.
 */
@Composable
private fun ManualFeedCard(onAdd: (FeedingEntity) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(FeedType.BREAST) }
    var side by remember { mutableStateOf(BreastSide.LEFT) }
    var amount by remember { mutableStateOf("") }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.feeding_log_title), style = MaterialTheme.typography.titleMedium)

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
            TimePickerField(label = stringResource(R.string.picker_at), millis = time, onChange = { time = it })

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    onAdd(
                        FeedingEntity(
                            type = type,
                            side = if (type == FeedType.BREAST) side else null,
                            amountMl = if (type == FeedType.BOTTLE) amount.toIntOrNull() else null,
                            startTime = time,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    amount = ""
                    notes = ""
                    time = System.currentTimeMillis()
                }) {
                    Text(stringResource(R.string.feeding_add))
                }
            }
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

private fun feedingSubtitle(context: Context, entry: FeedingEntity): String {
    val sep = context.getString(R.string.feeding_detail_separator)
    val parts = mutableListOf<String>()
    if (entry.type == FeedType.BREAST) {
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
