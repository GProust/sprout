package com.gproust.sprout.ui.diaper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.DiaperType
import com.gproust.sprout.ui.common.ChoiceChips
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

    Scaffold(topBar = { SproutTopBar("Diapers") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DiaperAddCard(onAdd = vm::add) }
            item {
                Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (diapers.isEmpty()) {
                item { EmptyHint("No diaper changes logged yet.") }
            }
            items(diapers, key = { it.id }) { entry ->
                EntryCard(
                    title = entry.type.label(),
                    subtitle = entry.notes.orEmpty(),
                    meta = formatTime(entry.time),
                    icon = Icons.Filled.BabyChangingStation,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

private fun DiaperType.label(): String = when (this) {
    DiaperType.WET -> "Wet"
    DiaperType.DIRTY -> "Dirty"
    DiaperType.MIXED -> "Mixed"
}

@Composable
private fun DiaperAddCard(onAdd: (DiaperEntity) -> Unit) {
    var type by remember { mutableStateOf(DiaperType.WET) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Log a change", style = MaterialTheme.typography.titleMedium)

            FieldLabel("Type")
            ChoiceChips(
                options = DiaperType.entries,
                selected = type,
                onSelect = { type = it },
                labelOf = { it.label() },
            )

            FieldLabel("Time")
            TimePickerField(label = "At", millis = time, onChange = { time = it })

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    onAdd(
                        DiaperEntity(
                            time = time,
                            type = type,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    notes = ""
                    time = System.currentTimeMillis()
                }) {
                    Text("Add change")
                }
            }
        }
    }
}
