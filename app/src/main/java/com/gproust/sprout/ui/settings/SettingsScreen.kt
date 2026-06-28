package com.gproust.sprout.ui.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.SproutApplication
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A selectable language: null tag = follow the system; null flag = use the globe icon. */
private data class LanguageChoice(
    val tag: String?,
    val label: String,
    @param:DrawableRes val flag: Int?,
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val current = AppLocale.currentTag(context)

    val choices = listOf(
        LanguageChoice(null, stringResource(R.string.settings_language_system), null),
        LanguageChoice("en", stringResource(R.string.language_en), R.drawable.flag_en),
        LanguageChoice("fr", stringResource(R.string.language_fr), R.drawable.flag_fr),
        LanguageChoice("de", stringResource(R.string.language_de), R.drawable.flag_de),
        LanguageChoice("es", stringResource(R.string.language_es), R.drawable.flag_es),
        LanguageChoice("it", stringResource(R.string.language_it), R.drawable.flag_it),
        LanguageChoice("pl", stringResource(R.string.language_pl), R.drawable.flag_pl),
        LanguageChoice("pt", stringResource(R.string.language_pt), R.drawable.flag_pt),
    )

    val scope = rememberCoroutineScope()
    val repository = remember { (context.applicationContext as SproutApplication).repository }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    var remindersEnabled by remember { mutableStateOf(FeedingReminderSettings.isEnabled(context)) }
    var intervalMinutes by remember { mutableIntStateOf(FeedingReminderSettings.intervalMinutes(context)) }

    fun reschedule() {
        scope.launch(Dispatchers.IO) { FeedingReminders.rescheduleAll(context, repository) }
    }

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
                    flag = choice.flag,
                    selected = isSelected(current, choice.tag),
                    onSelect = { chooseLanguage(context, choice.tag) },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
            item {
                FeedingReminderSection(
                    enabled = remindersEnabled,
                    intervalMinutes = intervalMinutes,
                    onToggle = { on ->
                        remindersEnabled = on
                        FeedingReminderSettings.setEnabled(context, on)
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        reschedule()
                    },
                    onSelectInterval = { minutes ->
                        intervalMinutes = minutes
                        FeedingReminderSettings.setIntervalMinutes(context, minutes)
                        reschedule()
                    },
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
private fun LanguageRow(label: String, @DrawableRes flag: Int?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagSlot(flag)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        RadioButton(selected = selected, onClick = null)
    }
}

/** A fixed-size leading slot: the country flag, or a globe for "System default". */
@Composable
private fun FlagSlot(@DrawableRes flag: Int?) {
    if (flag == null) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        Image(
            painter = painterResource(flag),
            contentDescription = null,
            modifier = Modifier
                .size(width = 30.dp, height = 20.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun FeedingReminderSection(
    enabled: Boolean,
    intervalMinutes: Int,
    onToggle: (Boolean) -> Unit,
    onSelectInterval: (Int) -> Unit,
) {
    val context = LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    stringResource(R.string.settings_feeding_reminders),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_feeding_reminders_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            FieldLabel(stringResource(R.string.settings_feeding_interval_label))
            ChoiceChips(
                options = FeedingReminderSettings.INTERVAL_CHOICES,
                selected = intervalMinutes,
                onSelect = onSelectInterval,
                labelOf = { formatDuration(context, it * 60_000L) },
            )
        }
    }
}
