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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.FoodHistory
import com.gproust.sprout.data.foodKey
import com.gproust.sprout.data.local.FoodPortion
import com.gproust.sprout.data.local.FoodReception
import com.gproust.sprout.data.local.MealFood
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.FieldLabel

/**
 * How many of the household's foods the form offers at once. Enough for the
 * week's usual suspects; typing narrows the rest.
 */
private const val MAX_SUGGESTIONS = 12

/**
 * The foods of one solid meal (BDR-20): each one a taste or a serving, and how
 * it went if the parent noticed.
 *
 * There is no list of foods behind this. [known] is the household's own — every
 * food it has logged before, most recent first — offered back as one-tap chips
 * and narrowed as the parent types, so the second time nobody types at all.
 * A new food starts as a taste; one logged before starts as whatever it was
 * last time.
 */
@Composable
fun MealFoodsEditor(
    foods: List<MealFood>,
    onChange: (List<MealFood>) -> Unit,
    known: List<FoodHistory>,
) {
    var typed by remember { mutableStateOf("") }

    fun add(name: String) {
        val clean = name.trim()
        val key = foodKey(clean)
        // A food already on this meal is not a second offer of it.
        if (key.isNotEmpty() && foods.none { foodKey(it.name) == key }) {
            val portion = known.firstOrNull { it.key == key }?.lastPortion ?: FoodPortion.TASTE
            onChange(foods + MealFood(clean, portion))
        }
        typed = ""
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
        label = { Text(stringResource(R.string.meal_food_add)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { add(typed) }),
        trailingIcon = {
            IconButton(onClick = { add(typed) }, enabled = typed.isNotBlank()) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.meal_food_add))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    val onMeal = foods.map { foodKey(it.name) }.toSet()
    val query = foodKey(typed)
    val suggestions = known
        .filter { it.key !in onMeal && (query.isEmpty() || it.key.contains(query)) }
        .take(MAX_SUGGESTIONS)
    if (suggestions.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { history ->
                AssistChip(
                    onClick = { add(history.name) },
                    label = { Text(history.name) },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    food.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.meal_food_remove, food.name),
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
