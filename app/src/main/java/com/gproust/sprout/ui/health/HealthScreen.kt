package com.gproust.sprout.ui.health

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.common.healingFieldLabel
import com.gproust.sprout.ui.common.label
import com.gproust.sprout.ui.common.moodEmoji
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HealthViewModel(private val repository: SproutRepository) : ViewModel() {
    val gaveBirth = repository.parentProfile
        .map { it?.gaveBirth ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val breastfeeding = repository.parentProfile
        .map { it?.breastfeeding ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val deliveryType = repository.parentProfile
        .map { it?.deliveryType }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val entries = repository.wellbeing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: WellbeingEntity) = viewModelScope.launch { repository.addWellbeing(entity) }
    fun delete(entity: WellbeingEntity) = viewModelScope.launch { repository.deleteWellbeing(entity) }
}

@Composable
fun HealthScreen(onBack: () -> Unit) {
    val vm: HealthViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val gaveBirth by vm.gaveBirth.collectAsState()
    val breastfeeding by vm.breastfeeding.collectAsState()
    val deliveryType by vm.deliveryType.collectAsState()
    val entries by vm.entries.collectAsState()
    val context = LocalContext.current

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_wellbeing), onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WellbeingAddCard(
                    gaveBirth = gaveBirth,
                    breastfeeding = breastfeeding,
                    deliveryType = deliveryType,
                    onAdd = vm::add,
                )
            }
            item {
                Text(
                    stringResource(R.string.history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (entries.isEmpty()) {
                item { EmptyHint(stringResource(R.string.wellbeing_empty)) }
            }
            items(entries, key = { it.id }) { entry ->
                EntryCard(
                    title = stringResource(R.string.wellbeing_mood_title, moodEmoji(entry.mood), entry.mood),
                    subtitle = wellbeingSubtitle(context, entry),
                    meta = formatDateTime(context, entry.time),
                    icon = Icons.Filled.Favorite,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

private fun wellbeingSubtitle(context: Context, entry: WellbeingEntity): String {
    val parts = buildList {
        entry.recovery?.let { add(context.getString(R.string.wellbeing_sub_healing, it.label(context))) }
        entry.bleeding?.let { add(context.getString(R.string.wellbeing_sub_bleeding, it.label(context))) }
        entry.breast?.let { add(context.getString(R.string.wellbeing_sub_breasts, it.label(context))) }
        if (!entry.notes.isNullOrBlank()) add(entry.notes)
    }
    return parts.joinToString(context.getString(R.string.feeding_detail_separator))
}

@Composable
private fun WellbeingAddCard(
    gaveBirth: Boolean,
    breastfeeding: Boolean,
    deliveryType: DeliveryType?,
    onAdd: (WellbeingEntity) -> Unit,
) {
    val context = LocalContext.current
    var mood by remember { mutableIntStateOf(3) }
    var bleeding by remember { mutableStateOf<Bleeding?>(null) }
    var recovery by remember { mutableStateOf<Recovery?>(null) }
    var breast by remember { mutableStateOf<BreastState?>(null) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.wellbeing_add_title), style = MaterialTheme.typography.titleMedium)

            FieldLabel(stringResource(R.string.field_mood))
            ChoiceChips(
                options = listOf(1, 2, 3, 4, 5),
                selected = mood,
                onSelect = { mood = it },
                labelOf = { "${moodEmoji(it)} $it" },
            )

            if (gaveBirth) {
                FieldLabel(healingFieldLabel(context, deliveryType))
                ChoiceChips(
                    options = Recovery.entries,
                    selected = recovery,
                    onSelect = { recovery = if (recovery == it) null else it },
                    labelOf = { it.label(context) },
                )

                FieldLabel(stringResource(R.string.field_bleeding))
                ChoiceChips(
                    options = Bleeding.entries,
                    selected = bleeding,
                    onSelect = { bleeding = if (bleeding == it) null else it },
                    labelOf = { it.label(context) },
                )
            }

            if (breastfeeding) {
                FieldLabel(stringResource(R.string.field_breast_comfort))
                ChoiceChips(
                    options = BreastState.entries,
                    selected = breast,
                    onSelect = { breast = if (breast == it) null else it },
                    labelOf = { it.label(context) },
                )
            }

            FieldLabel(stringResource(R.string.field_time))
            TimePickerField(label = stringResource(R.string.picker_at), millis = time, onChange = { time = it })

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    onAdd(
                        WellbeingEntity(
                            time = time,
                            mood = mood,
                            bleeding = if (gaveBirth) bleeding else null,
                            recovery = if (gaveBirth) recovery else null,
                            breast = if (breastfeeding) breast else null,
                            notes = notes.ifBlank { null },
                        ),
                    )
                    mood = 3
                    bleeding = null
                    recovery = null
                    breast = null
                    notes = ""
                    time = System.currentTimeMillis()
                }) {
                    Text(stringResource(R.string.wellbeing_add))
                }
            }
        }
    }
}
