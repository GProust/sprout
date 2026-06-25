package com.gproust.sprout.ui.mother

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.MotherHealthEntity
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MotherViewModel(private val repository: SproutRepository) : ViewModel() {
    val entries = repository.motherHealth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: MotherHealthEntity) = viewModelScope.launch { repository.addMotherHealth(entity) }
    fun delete(entity: MotherHealthEntity) = viewModelScope.launch { repository.deleteMotherHealth(entity) }
}

@Composable
fun MotherScreen(onBack: () -> Unit) {
    val vm: MotherViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val entries by vm.entries.collectAsState()

    Scaffold(topBar = { SproutTopBar("Mother's health", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MotherAddCard(onAdd = vm::add) }
            item {
                Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (entries.isEmpty()) {
                item { EmptyHint("No check-ins logged yet. Take care of yourself too 💚") }
            }
            items(entries, key = { it.id }) { entry ->
                EntryCard(
                    title = "${moodEmoji(entry.mood)} Mood ${entry.mood}/5",
                    subtitle = motherSubtitle(entry),
                    meta = formatDateTime(entry.time),
                    icon = Icons.Filled.Favorite,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

private fun motherSubtitle(entry: MotherHealthEntity): String {
    val parts = buildList {
        entry.bleeding?.let { add("Bleeding: ${it.label()}") }
        entry.breast?.let { add("Breasts: ${it.label()}") }
        if (!entry.notes.isNullOrBlank()) add(entry.notes)
    }
    return parts.joinToString(" · ")
}

private fun moodEmoji(mood: Int): String = when (mood) {
    1 -> "😢"
    2 -> "🙁"
    3 -> "😐"
    4 -> "🙂"
    else -> "😄"
}

private fun Bleeding.label(): String = when (this) {
    Bleeding.NONE -> "None"
    Bleeding.LIGHT -> "Light"
    Bleeding.MODERATE -> "Moderate"
    Bleeding.HEAVY -> "Heavy"
}

private fun BreastState.label(): String = when (this) {
    BreastState.NORMAL -> "Normal"
    BreastState.TENDER -> "Tender"
    BreastState.ENGORGED -> "Engorged"
    BreastState.PAINFUL -> "Painful"
}

@Composable
private fun MotherAddCard(onAdd: (MotherHealthEntity) -> Unit) {
    var mood by remember { mutableIntStateOf(3) }
    var bleeding by remember { mutableStateOf<Bleeding?>(null) }
    var breast by remember { mutableStateOf<BreastState?>(null) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("How are you feeling?", style = MaterialTheme.typography.titleMedium)

            FieldLabel("Mood")
            ChoiceChips(
                options = listOf(1, 2, 3, 4, 5),
                selected = mood,
                onSelect = { mood = it },
                labelOf = { "${moodEmoji(it)} $it" },
            )

            FieldLabel("Bleeding (lochia)")
            ChoiceChips(
                options = Bleeding.entries,
                selected = bleeding,
                onSelect = { bleeding = if (bleeding == it) null else it },
                labelOf = { it.label() },
            )

            FieldLabel("Breast comfort")
            ChoiceChips(
                options = BreastState.entries,
                selected = breast,
                onSelect = { breast = if (breast == it) null else it },
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
                        MotherHealthEntity(
                            time = time,
                            mood = mood,
                            bleeding = bleeding,
                            breast = breast,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    mood = 3
                    bleeding = null
                    breast = null
                    notes = ""
                    time = System.currentTimeMillis()
                }) {
                    Text("Add check-in")
                }
            }
        }
    }
}
