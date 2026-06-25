package com.gproust.sprout.ui.growth

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Monitor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.formatDate
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GrowthViewModel(private val repository: SproutRepository) : ViewModel() {
    val growth = repository.growth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(entity: GrowthEntity) = viewModelScope.launch { repository.addGrowth(entity) }
    fun delete(entity: GrowthEntity) = viewModelScope.launch { repository.deleteGrowth(entity) }
}

@Composable
fun GrowthScreen() {
    val vm: GrowthViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val growth by vm.growth.collectAsState()
    val lineColor = MaterialTheme.colorScheme.primary

    Scaffold(topBar = { SproutTopBar("Growth") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GrowthAddCard(onAdd = vm::add) }

            val weighed = growth.filter { it.weightGrams != null }.sortedBy { it.time }
            if (weighed.size >= 2) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Weight trend", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            WeightChart(
                                points = weighed.map { it.time to it.weightGrams!! },
                                color = lineColor,
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(formatDate(weighed.first().time), style = MaterialTheme.typography.labelSmall)
                                Text(formatDate(weighed.last().time), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            item {
                Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (growth.isEmpty()) {
                item { EmptyHint("No measurements logged yet.") }
            }
            items(growth, key = { it.id }) { entry ->
                EntryCard(
                    title = growthTitle(entry),
                    subtitle = entry.notes.orEmpty(),
                    meta = formatDate(entry.time),
                    icon = Icons.Filled.Monitor,
                    onDelete = { vm.delete(entry) },
                )
            }
        }
    }
}

private fun growthTitle(entry: GrowthEntity): String {
    val parts = buildList {
        entry.weightGrams?.let { add("%.2f kg".format(it / 1000.0)) }
        entry.heightMm?.let { add("%.1f cm".format(it / 10.0)) }
        entry.headMm?.let { add("head %.1f cm".format(it / 10.0)) }
    }
    return if (parts.isEmpty()) "Measurement" else parts.joinToString(" · ")
}

@Composable
private fun WeightChart(
    points: List<Pair<Long, Int>>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val minW = points.minOf { it.second }
    val maxW = points.maxOf { it.second }
    val span = (maxW - minW).coerceAtLeast(1)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padY = h * 0.1f
        val usableH = h - padY * 2

        val coords = points.mapIndexed { index, (_, grams) ->
            val x = if (points.size == 1) w / 2 else w * index / (points.size - 1)
            val norm = (grams - minW).toFloat() / span
            val y = padY + (1f - norm) * usableH
            Offset(x, y)
        }

        val path = Path().apply {
            coords.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(path, color = color, style = Stroke(width = 5f))
        coords.forEach { drawCircle(color = color, radius = 7f, center = it) }
    }
}

@Composable
private fun GrowthAddCard(onAdd: (GrowthEntity) -> Unit) {
    var weight by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var head by remember { mutableStateOf("") }
    var date by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Log a measurement", style = MaterialTheme.typography.titleMedium)

            FieldLabel("Weight")
            NumberField(label = "Weight", value = weight, onChange = { weight = it }, suffix = "g")
            FieldLabel("Height")
            NumberField(label = "Height", value = height, onChange = { height = it }, suffix = "cm")
            FieldLabel("Head circumference")
            NumberField(label = "Head", value = head, onChange = { head = it }, suffix = "cm")

            FieldLabel("Date")
            DatePickerField(label = "On", millis = date, onChange = { date = it })

            Spacer(Modifier.height(8.dp))
            NotesField(value = notes, onChange = { notes = it })

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    enabled = weight.isNotBlank() || height.isNotBlank() || head.isNotBlank(),
                    onClick = {
                        onAdd(
                            GrowthEntity(
                                time = date,
                                weightGrams = weight.toIntOrNull(),
                                heightMm = height.toIntOrNull()?.let { it * 10 },
                                headMm = head.toIntOrNull()?.let { it * 10 },
                                notes = notes.ifBlank { null },
                            ),
                        )
                        weight = ""
                        height = ""
                        head = ""
                        notes = ""
                        date = System.currentTimeMillis()
                    },
                ) {
                    Text("Add measurement")
                }
            }
        }
    }
}
