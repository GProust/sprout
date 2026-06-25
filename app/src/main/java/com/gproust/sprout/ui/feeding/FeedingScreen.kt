package com.gproust.sprout.ui.feeding

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
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedingViewModel(private val repository: SproutRepository) : ViewModel() {
    val feedings = repository.feedings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: FeedingEntity) = viewModelScope.launch { repository.addFeeding(entity) }
    fun delete(entity: FeedingEntity) = viewModelScope.launch { repository.deleteFeeding(entity) }
}

@Composable
fun FeedingScreen() {
    val vm: FeedingViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val feedings by vm.feedings.collectAsState()

    Scaffold(topBar = { SproutTopBar("Feeding") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { FeedingAddCard(onAdd = vm::add) }
            item {
                Text(
                    "History",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (feedings.isEmpty()) {
                item { EmptyHint("No feedings logged yet. Add your first above.") }
            }
            items(feedings, key = { it.id }) { entry ->
                EntryCard(
                    title = feedingTitle(entry),
                    subtitle = entry.notes.orEmpty(),
                    meta = formatTime(entry.startTime),
                    icon = Icons.Filled.LocalDrink,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

private fun feedingTitle(entry: FeedingEntity): String = when (entry.type) {
    FeedType.BREAST -> "Breast" + (entry.side?.let { " · ${it.label()}" } ?: "")
    FeedType.BOTTLE -> "Bottle" + (entry.amountMl?.let { " · $it ml" } ?: "")
    FeedType.SOLID -> "Solids"
}

private fun BreastSide.label(): String = when (this) {
    BreastSide.LEFT -> "Left"
    BreastSide.RIGHT -> "Right"
    BreastSide.BOTH -> "Both"
}

@Composable
private fun FeedingAddCard(onAdd: (FeedingEntity) -> Unit) {
    var type by remember { mutableStateOf(FeedType.BREAST) }
    var side by remember { mutableStateOf(BreastSide.LEFT) }
    var amount by remember { mutableStateOf("") }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Log a feeding", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            FieldLabel("Type")
            ChoiceChips(
                options = FeedType.entries,
                selected = type,
                onSelect = { type = it },
                labelOf = { it.label() },
            )

            when (type) {
                FeedType.BREAST -> {
                    FieldLabel("Side")
                    ChoiceChips(
                        options = BreastSide.entries,
                        selected = side,
                        onSelect = { side = it },
                        labelOf = { it.label() },
                    )
                }
                FeedType.BOTTLE -> {
                    FieldLabel("Amount")
                    NumberField(label = "Amount", value = amount, onChange = { amount = it }, suffix = "ml")
                }
                FeedType.SOLID -> Unit
            }

            FieldLabel("Time")
            TimePickerField(label = "At", millis = time, onChange = { time = it })

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
                    Text("Add feeding")
                }
            }
        }
    }
}

private fun FeedType.label(): String = when (this) {
    FeedType.BREAST -> "Breast"
    FeedType.BOTTLE -> "Bottle"
    FeedType.SOLID -> "Solids"
}
