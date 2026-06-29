@file:OptIn(ExperimentalLayoutApi::class)

package com.gproust.sprout.ui.diaper

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.StoolColor
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiaperViewModel(private val repository: SproutRepository) : ViewModel() {
    val diapers = repository.diapers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: DiaperEntity) = viewModelScope.launch { repository.addDiaper(entity) }
    fun delete(entity: DiaperEntity) = viewModelScope.launch { repository.deleteDiaper(entity) }
}

@Composable
fun DiaperScreen() {
    val vm: DiaperViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val diapers by vm.diapers.collectAsState()
    val context = LocalContext.current

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_diapers)) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DiaperAddCard(onAdd = vm::add) }
            item {
                Text(
                    stringResource(R.string.history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (diapers.isEmpty()) {
                item { EmptyHint(stringResource(R.string.diaper_empty)) }
            }
            items(diapers, key = { it.id }) { entry ->
                EntryCard(
                    title = diaperTitle(context, entry),
                    subtitle = diaperSubtitle(context, entry),
                    meta = formatTime(entry.time),
                    icon = Icons.Filled.BabyChangingStation,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

/** Short label for a stool colour, e.g. "Yellow". */
private fun StoolColor.label(context: Context): String = context.getString(
    when (this) {
        StoolColor.YELLOW -> R.string.stool_color_yellow
        StoolColor.GREEN -> R.string.stool_color_green
        StoolColor.BROWN -> R.string.stool_color_brown
        StoolColor.PALE -> R.string.stool_color_pale
        StoolColor.CLAY -> R.string.stool_color_clay
        StoolColor.WHITE -> R.string.stool_color_white
        StoolColor.BLACK -> R.string.stool_color_black
        StoolColor.RED -> R.string.stool_color_red
    },
)

/** Swatch colour for the picker (approximating the infant stool colour card). */
private fun StoolColor.swatch(): Color = when (this) {
    StoolColor.YELLOW -> Color(0xFFE7C24B)
    StoolColor.GREEN -> Color(0xFF7C8A4A)
    StoolColor.BROWN -> Color(0xFF7A5230)
    StoolColor.PALE -> Color(0xFFEADFB4)
    StoolColor.CLAY -> Color(0xFFD7D0BE)
    StoolColor.WHITE -> Color(0xFFF1EFE8)
    StoolColor.BLACK -> Color(0xFF36322C)
    StoolColor.RED -> Color(0xFFB23A2E)
}

/** True for pale swatches that need a dark check mark to stay legible. */
private fun StoolColor.isLight(): Boolean = when (this) {
    StoolColor.YELLOW, StoolColor.PALE, StoolColor.CLAY, StoolColor.WHITE -> true
    else -> false
}

/** History title from the checklist: "Urine", "Stool", or "Urine + stool". */
private fun diaperTitle(context: Context, entry: DiaperEntity): String = context.getString(
    when {
        entry.wet && entry.dirty -> R.string.diaper_both
        entry.dirty -> R.string.diaper_stool
        else -> R.string.diaper_urine
    },
)

/** Stool colour (if any) and notes, joined with a separator. */
private fun diaperSubtitle(context: Context, entry: DiaperEntity): String {
    val sep = context.getString(R.string.diaper_separator)
    val parts = mutableListOf<String>()
    if (entry.dirty) entry.stoolColor?.let { parts += it.label(context) }
    entry.notes?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(sep)
}

@Composable
private fun DiaperAddCard(onAdd: (DiaperEntity) -> Unit) {
    val context = LocalContext.current
    var wet by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var stoolColor by remember { mutableStateOf<StoolColor?>(null) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.diaper_log_title), style = MaterialTheme.typography.titleMedium)

            FieldLabel(stringResource(R.string.diaper_contents))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckChip(
                    label = stringResource(R.string.diaper_urine),
                    selected = wet,
                    onClick = { wet = !wet },
                )
                CheckChip(
                    label = stringResource(R.string.diaper_stool),
                    selected = dirty,
                    onClick = {
                        dirty = !dirty
                        if (!dirty) stoolColor = null
                    },
                )
            }

            if (dirty) {
                FieldLabel(stringResource(R.string.diaper_stool_color))
                StoolColorPicker(selected = stoolColor, onSelect = { stoolColor = it })
            }

            FieldLabel(stringResource(R.string.field_time))
            TimePickerField(label = stringResource(R.string.picker_at), millis = time, onChange = { time = it })

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    enabled = wet || dirty,
                    onClick = {
                        onAdd(
                            DiaperEntity(
                                time = time,
                                wet = wet,
                                dirty = dirty,
                                stoolColor = if (dirty) stoolColor else null,
                                notes = notes.ifBlank { null },
                            ),
                        )
                        wet = true
                        dirty = false
                        stoolColor = null
                        notes = ""
                        time = System.currentTimeMillis()
                    },
                ) {
                    Text(stringResource(R.string.diaper_add))
                }
            }
        }
    }
}

/** A multi-select chip with a check mark when selected (a checklist item). */
@Composable
private fun CheckChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

/** A row of predefined stool-colour swatches; tapping one selects/clears it. */
@Composable
private fun StoolColorPicker(selected: StoolColor?, onSelect: (StoolColor?) -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StoolColor.entries.forEach { color ->
            val isSelected = color == selected
            val ring = if (isSelected) {
                BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.swatch())
                    .border(ring, CircleShape)
                    .clickable { onSelect(if (isSelected) null else color) }
                    .semantics { contentDescription = color.label(context) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (color.isLight()) Color(0xFF3A352F) else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
