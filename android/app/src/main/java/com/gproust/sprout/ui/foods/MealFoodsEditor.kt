@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.gproust.sprout.ui.foods

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbsUpDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.FoodCatalog
import com.gproust.sprout.data.FoodCategory
import com.gproust.sprout.data.FoodHistory
import com.gproust.sprout.data.foodIdentity
import com.gproust.sprout.data.foodKey
import com.gproust.sprout.data.local.FoodPortion
import com.gproust.sprout.data.local.FoodReception
import com.gproust.sprout.data.local.MealFood
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.FieldLabel
import java.text.Collator
import java.text.Normalizer

/**
 * How many of the household's foods the form offers before anything is typed.
 * Enough for the week's usual suspects; typing reaches the rest.
 */
private const val MAX_RECENT = 12

/** How many matches a search shows at once — a word narrows it further. */
private const val MAX_MATCHES = 24

/**
 * Something the parent can tap to put on the meal: a food from Sprout's list, or
 * one the household has logged before. [food] is what it adds; [key] is its
 * [foodIdentity], so the same carrot is never offered twice.
 */
private data class FoodChoice(
    val label: String,
    val food: MealFood,
    val key: String,
    val category: FoodCategory? = null,
)

/**
 * What a search compares: [foodKey] with the accents taken off, so "pates"
 * finds "pâtes" and "epinards" finds "épinards". For searching only — two foods
 * are never merged by it.
 */
private fun searchKey(text: String): String =
    Normalizer.normalize(foodKey(text), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * The foods of one solid meal (BDR-20): each one a taste or a serving, and how
 * it went if the parent noticed.
 *
 * One field does both jobs. Typing searches Sprout's list — vegetables, meat
 * and fish, in the phone's language — and every food the household has logged
 * before; the three shelves can be browsed without typing at all. Anything
 * that is not found is added as typed (a ratatouille, a grandmother's soup) and
 * is one tap away from then on. A new food starts as a taste; one logged before
 * starts as whatever it was last time.
 */
@Composable
fun MealFoodsEditor(
    foods: List<MealFood>,
    onChange: (List<MealFood>) -> Unit,
    known: List<FoodHistory>,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var typed by remember { mutableStateOf("") }
    var shelf by remember { mutableStateOf<FoodCategory?>(null) }

    // The list in this phone's language, rebuilt when the language changes.
    val catalog = remember(configuration) {
        FoodCatalog.foods.map { item ->
            val label = catalogName(context, item.id) ?: item.id
            FoodChoice(label, MealFood(label, ref = item.id), "ref:${item.id}", item.category)
        }
    }
    val household = known.map { history ->
        FoodChoice(
            label = history.displayName(context),
            food = MealFood(history.displayName(context), history.lastPortion, ref = history.ref),
            key = history.key,
        )
    }
    val lastPortion = known.associate { it.key to it.lastPortion }
    val onMeal = foods.map(::foodIdentity).toSet()
    val query = searchKey(typed)
    val everything = (household + catalog).distinctBy { it.key }
    val exact = everything.firstOrNull { searchKey(it.label) == query }

    fun add(choice: FoodChoice) {
        if (choice.key !in onMeal) {
            onChange(foods + choice.food.copy(portion = lastPortion[choice.key] ?: choice.food.portion))
        }
        typed = ""
    }

    // What was typed, as a food: the one it names on the list or in the
    // household if there is one, so "carotte" typed out in full is still the
    // carrot from the list, and otherwise a food of the household's own.
    fun addTyped() {
        val clean = typed.trim()
        when {
            query.isEmpty() -> typed = ""
            exact != null -> add(exact)
            else -> add(FoodChoice(clean, MealFood(clean), foodKey(clean)))
        }
    }

    FieldLabel(stringResource(R.string.meal_foods))
    if (foods.isEmpty()) {
        Text(
            stringResource(R.string.meal_foods_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    foods.forEachIndexed { i, food ->
        MealFoodRow(
            food = food,
            onChange = { changed -> onChange(foods.mapIndexed { j, f -> if (j == i) changed else f }) },
            onRemove = { onChange(foods.filterIndexed { j, _ -> j != i }) },
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text(stringResource(R.string.meal_food_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { addTyped() }),
        trailingIcon = {
            IconButton(onClick = { addTyped() }, enabled = query.isNotEmpty()) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.meal_food_add))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    // The three shelves, to browse without knowing what to type. Tapping the
    // chosen one again puts it away.
    ChoiceChips(
        options = FoodCategory.entries,
        selected = shelf,
        onSelect = { shelf = if (it == shelf) null else it },
        labelOf = { it.label(context) },
    )

    val collator = remember(configuration) { Collator.getInstance() }
    val shown = when {
        shelf != null -> catalog
            .filter { it.category == shelf && (query.isEmpty() || searchKey(it.label).contains(query)) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        query.isEmpty() -> household.take(MAX_RECENT)
        else -> everything.filter { searchKey(it.label).contains(query) }.take(MAX_MATCHES)
    }.filter { it.key !in onMeal }

    if (shelf == null && query.isEmpty() && shown.isNotEmpty()) {
        Text(
            stringResource(R.string.meal_foods_recent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    // Something typed that matches nothing exactly is offered as a food of its
    // own, by name, so adding the ratatouille is a tap and not a hunt for the
    // keyboard's Done key.
    val offerTyped = query.isNotEmpty() && exact == null
    if (shown.isNotEmpty() || offerTyped) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEach { choice ->
                AssistChip(
                    onClick = { add(choice) },
                    label = { Text(choice.label) },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
            if (offerTyped) {
                AssistChip(
                    onClick = { addTyped() },
                    label = { Text(stringResource(R.string.meal_food_add_named, typed.trim())) },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

/** One food on the meal: its name, a taste or a serving, and how it went. */
@Composable
private fun MealFoodRow(
    food: MealFood,
    onChange: (MealFood) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    food.displayName(context),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.meal_food_remove, food.displayName(context)),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            ChoiceChips(
                options = FoodPortion.entries,
                selected = food.portion,
                onSelect = { onChange(food.copy(portion = it)) },
                labelOf = { it.label(context) },
            )
            // Optional, and tapping the chosen one again clears it: "not noted"
            // is a real answer, and one a parent must be able to go back to.
            ChoiceChips(
                options = FoodReception.entries,
                selected = food.reception,
                onSelect = { onChange(food.copy(reception = if (it == food.reception) null else it)) },
                labelOf = { it.label(context) },
            )
        }
    }
}

fun FoodPortion.label(context: Context): String = context.getString(
    when (this) {
        FoodPortion.TASTE -> R.string.food_portion_taste
        FoodPortion.SERVING -> R.string.food_portion_serving
    },
)

fun FoodReception.label(context: Context): String = context.getString(
    when (this) {
        FoodReception.LIKED -> R.string.food_reception_liked
        FoodReception.MIXED -> R.string.food_reception_mixed
        FoodReception.REFUSED -> R.string.food_reception_refused
    },
)

/**
 * Each answer's own icon, so the word is never the only thing that tells them
 * apart at a glance — and never a colour either: *refused* is a fact about one
 * meal, not a warning (BDR-20).
 */
val FoodReception.icon: ImageVector
    get() = when (this) {
        FoodReception.LIKED -> Icons.Filled.ThumbUp
        FoodReception.MIXED -> Icons.Filled.ThumbsUpDown
        FoodReception.REFUSED -> Icons.Filled.ThumbDown
    }
