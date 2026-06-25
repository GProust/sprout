package com.gproust.sprout.ui.sleep

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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SleepViewModel(private val repository: SproutRepository) : ViewModel() {
    val sleeps = repository.sleeps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: SleepEntity) = viewModelScope.launch { repository.addSleep(entity) }
    fun delete(entity: SleepEntity) = viewModelScope.launch { repository.deleteSleep(entity) }
}

@Composable
fun SleepScreen() {
    val vm: SleepViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val sleeps by vm.sleeps.collectAsState()

    Scaffold(topBar = { SproutTopBar("Sleep") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SleepAddCard(onAdd = vm::add) }
            item {
                Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (sleeps.isEmpty()) {
                item { EmptyHint("No sleep logged yet.") }
            }
            items(sleeps, key = { it.id }) { entry ->
                val subtitle = entry.endTime?.let {
                    "${formatTime(entry.startTime)} – ${formatTime(it)} · ${formatDuration(it - entry.startTime)}"
                } ?: "Started ${formatTime(entry.startTime)} (ongoing)"
                EntryCard(
                    title = "Sleep",
                    subtitle = if (entry.notes.isNullOrBlank()) subtitle else "$subtitle\n${entry.notes}",
                    meta = formatTime(entry.startTime),
                    icon = Icons.Filled.Bedtime,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

@Composable
private fun SleepAddCard(onAdd: (SleepEntity) -> Unit) {
    var start by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var end by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hasEnded by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Log sleep", style = MaterialTheme.typography.titleMedium)

            FieldLabel("Start")
            TimePickerField(label = "From", millis = start, onChange = { start = it })

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Already woke up")
                Switch(checked = hasEnded, onCheckedChange = { hasEnded = it })
            }

            if (hasEnded) {
                FieldLabel("End")
                TimePickerField(label = "To", millis = end, onChange = { end = it })
            }

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    onAdd(
                        SleepEntity(
                            startTime = start,
                            endTime = if (hasEnded) end else null,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    notes = ""
                    start = System.currentTimeMillis()
                    end = System.currentTimeMillis()
                }) {
                    Text("Add sleep")
                }
            }
        }
    }
}
