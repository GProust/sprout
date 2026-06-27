package com.gproust.sprout.ui.health

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.CoParentHealthEntity
import com.gproust.sprout.data.local.MotherHealthEntity
import com.gproust.sprout.data.local.ParentRole
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.common.label
import com.gproust.sprout.ui.common.moodEmoji
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HealthViewModel(private val repository: SproutRepository) : ViewModel() {
    val role = repository.parentProfile
        .map { it?.role }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val motherEntries = repository.motherHealth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val coParentEntries = repository.coParentHealth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMother(entity: MotherHealthEntity) = viewModelScope.launch { repository.addMotherHealth(entity) }
    fun deleteMother(entity: MotherHealthEntity) = viewModelScope.launch { repository.deleteMotherHealth(entity) }
    fun addCoParent(entity: CoParentHealthEntity) = viewModelScope.launch { repository.addCoParentHealth(entity) }
    fun deleteCoParent(entity: CoParentHealthEntity) = viewModelScope.launch { repository.deleteCoParentHealth(entity) }
}

@Composable
fun HealthScreen(onBack: () -> Unit) {
    val vm: HealthViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val role by vm.role.collectAsState()
    val motherEntries by vm.motherEntries.collectAsState()
    val coParentEntries by vm.coParentEntries.collectAsState()

    // Default to the tab the current parent can edit.
    var tab by remember(role) { mutableIntStateOf(if (role == ParentRole.CO_PARENT) 1 else 0) }

    Scaffold(topBar = { SproutTopBar("Wellbeing", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mother") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Co-parent") })
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (tab) {
                    0 -> MotherTab(
                        editable = role == ParentRole.MOTHER,
                        entries = motherEntries,
                        onAdd = vm::addMother,
                        onDelete = vm::deleteMother,
                    )
                    else -> CoParentTab(
                        editable = role == ParentRole.CO_PARENT,
                        entries = coParentEntries,
                        onAdd = vm::addCoParent,
                        onDelete = vm::deleteCoParent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewOnlyNote(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MotherTab(
    editable: Boolean,
    entries: List<MotherHealthEntity>,
    onAdd: (MotherHealthEntity) -> Unit,
    onDelete: (MotherHealthEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (editable) {
                MotherAddCard(onAdd = onAdd)
            } else {
                ViewOnlyNote("Only the mother can add entries here. Her check-ins will appear once syncing is set up.")
            }
        }
        item {
            Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (entries.isEmpty()) {
            item { EmptyHint("No check-ins logged yet.") }
        }
        items(entries, key = { it.id }) { entry ->
            EntryCard(
                title = "${moodEmoji(entry.mood)} Mood ${entry.mood}/5",
                subtitle = motherSubtitle(entry),
                meta = formatDateTime(entry.time),
                icon = Icons.Filled.Favorite,
                onDelete = { onDelete(entry) },
            )
        }
    }
}

@Composable
private fun CoParentTab(
    editable: Boolean,
    entries: List<CoParentHealthEntity>,
    onAdd: (CoParentHealthEntity) -> Unit,
    onDelete: (CoParentHealthEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (editable) {
                CoParentAddCard(onAdd = onAdd)
            } else {
                ViewOnlyNote("Only the co-parent can add entries here. Their check-ins will appear once syncing is set up.")
            }
        }
        item {
            Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (entries.isEmpty()) {
            item { EmptyHint("No check-ins logged yet.") }
        }
        items(entries, key = { it.id }) { entry ->
            EntryCard(
                title = "${moodEmoji(entry.mood)} Mood ${entry.mood}/5",
                subtitle = entry.notes.orEmpty(),
                meta = formatDateTime(entry.time),
                icon = Icons.Filled.Person,
                onDelete = { onDelete(entry) },
            )
        }
    }
}

private fun motherSubtitle(entry: MotherHealthEntity): String {
    val parts = buildList {
        entry.recovery?.let { add("Healing: ${it.label()}") }
        entry.bleeding?.let { add("Bleeding: ${it.label()}") }
        entry.breast?.let { add("Breasts: ${it.label()}") }
        if (!entry.notes.isNullOrBlank()) add(entry.notes)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun MotherAddCard(onAdd: (MotherHealthEntity) -> Unit) {
    var mood by remember { mutableIntStateOf(3) }
    var bleeding by remember { mutableStateOf<Bleeding?>(null) }
    var breast by remember { mutableStateOf<BreastState?>(null) }
    var recovery by remember { mutableStateOf<Recovery?>(null) }
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

            FieldLabel("Recovery / healing")
            ChoiceChips(
                options = Recovery.entries,
                selected = recovery,
                onSelect = { recovery = if (recovery == it) null else it },
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
                            recovery = recovery,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    mood = 3
                    bleeding = null
                    breast = null
                    recovery = null
                    notes = ""
                    time = System.currentTimeMillis()
                }) {
                    Text("Add check-in")
                }
            }
        }
    }
}

@Composable
private fun CoParentAddCard(onAdd: (CoParentHealthEntity) -> Unit) {
    var mood by remember { mutableIntStateOf(3) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("How are you doing?", style = MaterialTheme.typography.titleMedium)

            FieldLabel("Mood")
            ChoiceChips(
                options = listOf(1, 2, 3, 4, 5),
                selected = mood,
                onSelect = { mood = it },
                labelOf = { "${moodEmoji(it)} $it" },
            )

            FieldLabel("Time")
            TimePickerField(label = "At", millis = time, onChange = { time = it })

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    onAdd(
                        CoParentHealthEntity(
                            time = time,
                            mood = mood,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    mood = 3
                    notes = ""
                    time = System.currentTimeMillis()
                }) {
                    Text("Add check-in")
                }
            }
        }
    }
}
