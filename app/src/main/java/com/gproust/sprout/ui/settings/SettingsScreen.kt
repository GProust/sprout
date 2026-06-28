package com.gproust.sprout.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.ui.common.SproutTopBar

/** A selectable language: null tag = follow the system. */
private data class LanguageChoice(val tag: String?, val label: String)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val current = AppLocale.currentTag(context)

    val choices = listOf(
        LanguageChoice(null, stringResource(R.string.settings_language_system)),
        LanguageChoice("en", stringResource(R.string.language_en)),
        LanguageChoice("fr", stringResource(R.string.language_fr)),
    )

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_settings), onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(choices.size) { i ->
                val choice = choices[i]
                LanguageRow(
                    label = choice.label,
                    selected = isSelected(current, choice.tag),
                    onSelect = { chooseLanguage(context, choice.tag) },
                )
            }
        }
    }
}

/** A tag matches the active selection if both are "system", or share the base language. */
private fun isSelected(current: String?, tag: String?): Boolean =
    if (tag == null) current.isNullOrBlank()
    else current?.substringBefore('-')?.equals(tag, ignoreCase = true) == true

private fun chooseLanguage(context: Context, tag: String?) {
    if (AppLocale.apply(context, tag)) {
        context.findActivity()?.recreate()
    }
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
