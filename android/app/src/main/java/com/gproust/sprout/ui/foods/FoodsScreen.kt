@file:OptIn(ExperimentalLayoutApi::class)

package com.gproust.sprout.ui.foods

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.FoodHistory
import com.gproust.sprout.data.FoodOffer
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.foodHistories
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.FoodReception
import com.gproust.sprout.data.local.MealFood
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.ui.common.AddEntryFab
import com.gproust.sprout.ui.common.AddEntrySheet
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.ConfirmDeleteDialog
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.dayLabel
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.feeding.EditFeedingDialog
import com.gproust.sprout.ui.feeding.FeedingForm
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import java.text.Collator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the First foods page draws from: the baby's meals, read back per food. */
data class FoodsState(
    /** The active baby's feeds, so a food's meal can be opened to correct. */
    val feedings: List<FeedingEntity> = emptyList(),
    /** The active baby's foods, most recently offered first. */
    val foods: List<FoodHistory> = emptyList(),
    /** The household's foods, which the meal form offers back. */
    val known: List<FoodHistory> = emptyList(),
)

/**
 * The foods a baby has been offered, one at a time (BDR-20).
 *
 * There is no food log behind this page: a solid meal lists the foods that were
 * in it, and everything here is read back from those meals. Logging from here
 * writes an ordinary solid meal, exactly as the feeding screen does.
 */
class FoodsViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {

    /**
     * One state rather than three, so a recomposition never pairs a food's
     * count with a list of meals it was not counted from.
     */
    val state = combine(repository.feedings, repository.householdMealsWithFoods) { feedings, household ->
        FoodsState(feedings, foodHistories(feedings), foodHistories(household))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FoodsState())

    /** A meal is a feed: logging one moves the feeding reminder, as it always has. */
    fun save(entity: FeedingEntity) = viewModelScope.launch {
        repository.addFeeding(entity)
        FeedingReminders.rescheduleActiveBaby(context, repository)
    }

    fun delete(entity: FeedingEntity) = viewModelScope.launch {
        repository.deleteFeeding(entity)
        FeedingReminders.rescheduleActiveBaby(context, repository)
    }

    /**
     * Makes [food] into [to] on every meal of this baby's that named it — the
     * baby whose page this is, found from one of the meals the food was in.
     */
    fun correct(food: FoodHistory, to: MealFood) = viewModelScope.launch {
        val uid = food.offers.firstOrNull()?.feedUid ?: return@launch
        val babyId = state.value.feedings.firstOrNull { it.uid == uid }?.babyId ?: return@launch
        repository.relabelFood(babyId, from = food.key, to = to)
    }
}

/** The two orders a parent can read the list in — neither of them by how well a food is going. */
private enum class FoodOrder { RECENT, NAME }

@Composable
fun FoodsScreen(
    onBack: () -> Unit = {},
    vm: FoodsViewModel = viewModel(factory = rememberSproutViewModelFactory()),
) {
    val state by vm.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FeedingEntity?>(null) }
    var deleting by remember { mutableStateOf<FeedingEntity?>(null) }
    var correcting by remember { mutableStateOf<FoodHistory?>(null) }
    var order by rememberSaveable { mutableStateOf(FoodOrder.RECENT) }

    if (adding) {
        AddEntrySheet(
            title = stringResource(R.string.foods_log_meal),
            onDismiss = { adding = false },
        ) {
            FeedingForm(
                initial = null,
                submitLabel = stringResource(R.string.feeding_add),
                onSubmit = { vm.save(it); adding = false },
                knownFoods = state.known,
                fixedType = FeedType.SOLID,
            )
        }
    }

    editing?.let { meal ->
        EditFeedingDialog(
            entry = meal,
            knownFoods = state.known,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { deleting = meal },
            title = stringResource(R.string.meal_edit_title),
            fixedType = FeedType.SOLID,
        )
    }

    deleting?.let { meal ->
        ConfirmDeleteDialog(
            onConfirm = { vm.delete(meal); deleting = null; editing = null },
            onDismiss = { deleting = null },
        )
    }

    correcting?.let { food ->
        CorrectFoodDialog(
            food = food,
            known = state.known,
            onConfirm = { to -> vm.correct(food, to); correcting = null },
            onDismiss = { correcting = null },
        )
    }

    val context = LocalContext.current
    val collator = remember { Collator.getInstance() }
    val foods = remember(state.foods, order, context) {
        when (order) {
            FoodOrder.RECENT -> state.foods
            FoodOrder.NAME -> state.foods.sortedWith { a, b ->
                collator.compare(a.displayName(context), b.displayName(context))
            }
        }
    }
    val now = System.currentTimeMillis()

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_foods), onBack = onBack) },
        floatingActionButton = {
            AddEntryFab(stringResource(R.string.foods_log_meal)) { adding = true }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("foodsList"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (foods.isEmpty()) {
                item { EmptyHint(stringResource(R.string.foods_empty)) }
            } else {
                item(key = "header") {
                    val recent = stringResource(R.string.foods_sort_recent)
                    val byName = stringResource(R.string.foods_sort_name)
                    Column {
                        Text(
                            pluralStringResource(R.plurals.foods_count, foods.size, foods.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ChoiceChips(
                            options = FoodOrder.entries,
                            selected = order,
                            onSelect = { order = it },
                            labelOf = {
                                when (it) {
                                    FoodOrder.RECENT -> recent
                                    FoodOrder.NAME -> byName
                                }
                            },
                        )
                    }
                }
                items(foods, key = { it.key }) { food ->
                    FoodCard(
                        food = food,
                        now = now,
                        onOpenMeal = { uid -> editing = state.feedings.firstOrNull { it.uid == uid } },
                        onCorrect = { correcting = food },
                    )
                }
            }
        }
    }
}

/**
 * One food: how many meals it was in, tastes and servings apart, the first and
 * the last time, and how it went when that was noted. Its meals are behind
 * *Details*, each one opening the meal itself.
 *
 * No colour means anything here and nothing is ranked: a count is a fact about
 * the meals that were logged, never progress toward a number Sprout chose.
 */
@Composable
private fun FoodCard(food: FoodHistory, now: Long, onOpenMeal: (String) -> Unit, onCorrect: () -> Unit) {
    val context = LocalContext.current
    // Android trims the spaces off the resource's " · ", so they go back on
    // here; the dot itself stays the translators' to change.
    val sep = " ${stringResource(R.string.feeding_detail_separator).trim()} "
    var expanded by rememberSaveable(food.key) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    food.displayName(context),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pluralStringResource(R.plurals.food_times_offered, food.timesOffered, food.timesOffered),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                portionsLine(context, food, sep),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                datesLine(context, food, now, sep),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val noted = FoodReception.entries.filter { food.timesReceived(it) > 0 }
            if (noted.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    noted.forEach { reception ->
                        val count = food.timesReceived(reception)
                        val spoken = stringResource(R.string.food_reception_count, reception.label(context), count)
                        // The icon and the number are drawn; the sentence is
                        // what a screen reader gets, so it hears "Refused: 2"
                        // rather than "thumb down, 2".
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                        ) {
                            Icon(
                                reception.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(start = 4.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.action_details))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            TextButton(onClick = onCorrect) {
                Text(stringResource(R.string.food_correct))
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                food.offers.forEach { offer ->
                    Text(
                        offerLine(context, offer, now, sep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMeal(offer.feedUid) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Correcting what a food was, after the fact (BDR-20): "carottes" typed by
 * hand made into the carrot from the list, so its meals stop counting as a
 * food of their own — or a food merged into one the household already has.
 *
 * Choosing is one step and applying another, with the change spelled out in
 * between: it rewrites every meal of this baby's that named the food.
 */
@Composable
private fun CorrectFoodDialog(
    food: FoodHistory,
    known: List<FoodHistory>,
    onConfirm: (MealFood) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var target by remember(food.key) { mutableStateOf<MealFood?>(null) }
    val name = food.displayName(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.food_correct_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val chosen = target
                if (chosen == null) {
                    Text(stringResource(R.string.food_correct_hint), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    FoodSearch(known = known, hidden = setOf(food.key), onPick = { target = it })
                } else {
                    val meals = pluralStringResource(R.plurals.food_correct_meals, food.timesOffered, food.timesOffered)
                    Text(
                        stringResource(R.string.food_correct_summary, name, chosen.displayName(context), meals),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { target = null }) {
                        Text(stringResource(R.string.food_correct_change))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onConfirm) }, enabled = target != null) {
                Text(stringResource(R.string.food_correct))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** "3 tastes · 2 servings" — whichever of the two there are. */
private fun portionsLine(context: Context, food: FoodHistory, sep: String): String = listOfNotNull(
    food.tastes.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.food_tastes, it, it) },
    food.servings.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.food_servings, it, it) },
).joinToString(sep)

/** "First: 3 Sep · Last: Today", or only the first when there has been one. */
private fun datesLine(context: Context, food: FoodHistory, now: Long, sep: String): String {
    val first = context.getString(R.string.food_first, dayLabel(context, food.firstOffered, now))
    if (food.timesOffered == 1) return first
    return first + sep + context.getString(R.string.food_last, dayLabel(context, food.lastOffered, now))
}

/** "Yesterday 12:30 · A serving · Liked it" for one meal the food was in. */
private fun offerLine(context: Context, offer: FoodOffer, now: Long, sep: String): String = listOfNotNull(
    "${dayLabel(context, offer.time, now)} ${formatTime(offer.time)}",
    offer.portion.label(context),
    offer.reception?.label(context),
).joinToString(sep)
