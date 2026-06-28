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
    val context = LocalContext.current

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_feeding)) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { FeedingAddCard(onAdd = vm::add) }
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
                    subtitle = entry.notes.orEmpty(),
                    meta = formatTime(entry.startTime),
                    icon = Icons.Filled.LocalDrink,
                    onDelete = { vm.delete(entry) },
                )
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

@Composable
private fun FeedingAddCard(onAdd: (FeedingEntity) -> Unit) {
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
