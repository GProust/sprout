package com.gproust.sprout.ui.profile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.notifications.effectiveFeedingReminder
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import com.gproust.sprout.ui.settings.FeedingReminderSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {
    val babies = repository.babies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archived = repository.archivedBabies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeBabyId = repository.parentProfile
        .map { it?.activeBabyId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun addBaby(name: String, birthDate: Long) =
        viewModelScope.launch { repository.addBaby(name, birthDate) }

    fun updateBaby(baby: BabyEntity) =
        viewModelScope.launch { repository.updateBaby(baby) }

    fun setActive(id: Long) = viewModelScope.launch { repository.setActiveBaby(id) }
    fun archive(id: Long) = viewModelScope.launch { repository.archiveBaby(id) }
    fun restore(id: Long) = viewModelScope.launch { repository.restoreBaby(id) }
    fun delete(id: Long) = viewModelScope.launch { repository.deleteBaby(id) }

    /**
     * Set ([enabled]/[intervalMinutes] non-null) or clear (both null) a baby's
     * feeding-reminder override, then re-arm its alarm with the new effective setting.
     */
    fun setFeedingReminderOverride(baby: BabyEntity, enabled: Boolean?, intervalMinutes: Int?) =
        viewModelScope.launch {
            repository.updateBaby(
                baby.copy(
                    feedingReminderEnabled = enabled,
                    feedingReminderIntervalMinutes = intervalMinutes,
                ),
            )
            FeedingReminders.rescheduleForBaby(context, repository, baby.id)
        }
}

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val vm: ProfileViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val babies by vm.babies.collectAsState()
    val archived by vm.archived.collectAsState()
    val activeId by vm.activeBabyId.collectAsState()
    val now = remember { System.currentTimeMillis() }

    // editorOpen with editorBaby == null means "adding a new baby".
    var editorOpen by remember { mutableStateOf(false) }
    var editorBaby by remember { mutableStateOf<BabyEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<BabyEntity?>(null) }
    var reminderTarget by remember { mutableStateOf<BabyEntity?>(null) }

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_babies), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (babies.isEmpty()) {
                Text(
                    stringResource(R.string.babies_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            babies.forEach { baby ->
                BabyCard(
                    baby = baby,
                    now = now,
                    isActive = baby.id == activeId,
                    onMakeActive = { vm.setActive(baby.id) },
                    onEdit = { editorBaby = baby; editorOpen = true },
                    onConfigureReminder = { reminderTarget = baby },
                    onArchive = { vm.archive(baby.id) },
                    onDelete = { deleteTarget = baby },
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = { editorBaby = null; editorOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.baby_add))
            }

            if (archived.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.babies_not_tracking),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.babies_not_tracking_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                archived.forEach { baby ->
                    ArchivedRow(
                        baby = baby,
                        now = now,
                        onRestore = { vm.restore(baby.id) },
                        onDelete = { deleteTarget = baby },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (editorOpen) {
        BabyEditorDialog(
            initial = editorBaby,
            onDismiss = { editorOpen = false },
            onSave = { name, birthDate ->
                val current = editorBaby
                if (current == null) {
                    vm.addBaby(name, birthDate)
                } else {
                    vm.updateBaby(current.copy(name = name.trim(), birthDate = birthDate))
                }
                editorOpen = false
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.baby_delete_title, target.name)) },
            text = { Text(stringResource(R.string.baby_delete_body, target.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(target.id); deleteTarget = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    reminderTarget?.let { target ->
        FeedingReminderOverrideDialog(
            baby = target,
            onDismiss = { reminderTarget = null },
            onSave = { enabled, intervalMinutes ->
                vm.setFeedingReminderOverride(target, enabled, intervalMinutes)
                reminderTarget = null
            },
        )
    }
}

@Composable
private fun BabyCard(
    baby: BabyEntity,
    now: Long,
    isActive: Boolean,
    onMakeActive: () -> Unit,
    onEdit: () -> Unit,
    onConfigureReminder: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Cake,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        baby.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        babyAge(context, baby.birthDate, now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        feedingReminderSummary(context, baby),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.baby_active),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.baby_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = onMakeActive) {
                        Text(stringResource(R.string.baby_make_active))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.cd_edit_baby, baby.name),
                    )
                }
                IconButton(onClick = onConfigureReminder) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.cd_feeding_reminder_baby, baby.name),
                    )
                }
                IconButton(onClick = onArchive) {
                    Icon(
                        Icons.Filled.Archive,
                        contentDescription = stringResource(R.string.cd_stop_tracking_baby, baby.name),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete_baby, baby.name),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedRow(
    baby: BabyEntity,
    now: Long,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(baby.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    babyAge(context, baby.birthDate, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Filled.Unarchive,
                    contentDescription = stringResource(R.string.cd_restore_baby, baby.name),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_baby, baby.name),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BabyEditorDialog(
    initial: BabyEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, birthDate: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var birthDate by remember { mutableLongStateOf(initial?.birthDate ?: System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) {
                    stringResource(R.string.baby_add)
                } else {
                    stringResource(R.string.baby_editor_edit, initial.name)
                },
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_baby_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel(stringResource(R.string.field_date_of_birth))
                DatePickerField(
                    label = stringResource(R.string.picker_born),
                    millis = birthDate,
                    onChange = { birthDate = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, birthDate) },
            ) {
                Text(
                    if (initial == null) {
                        stringResource(R.string.action_add)
                    } else {
                        stringResource(R.string.action_save)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Whether a baby carries its own feeding-reminder override (rather than following the default). */
private fun BabyEntity.hasFeedingReminderOverride(): Boolean =
    feedingReminderEnabled != null || feedingReminderIntervalMinutes != null

/** A one-line summary of a baby's effective feeding reminder, for the baby card. */
private fun feedingReminderSummary(context: Context, baby: BabyEntity): String {
    if (!baby.hasFeedingReminderOverride()) {
        return context.getString(R.string.baby_reminder_summary_default)
    }
    val eff = effectiveFeedingReminder(context, baby)
    return if (!eff.enabled) {
        context.getString(R.string.baby_reminder_summary_off)
    } else {
        context.getString(
            R.string.baby_reminder_summary_every,
            formatDuration(context, eff.intervalMinutes * 60_000L),
        )
    }
}

/**
 * Lets a baby either follow the app-wide feeding-reminder default or carry its
 * own override (its own on/off and interval). Clearing "custom" wipes both
 * columns back to null so the baby tracks the global default again.
 */
@Composable
private fun FeedingReminderOverrideDialog(
    baby: BabyEntity,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean?, intervalMinutes: Int?) -> Unit,
) {
    val context = LocalContext.current
    val globalEnabled = remember { FeedingReminderSettings.isEnabled(context) }
    val globalInterval = remember { FeedingReminderSettings.intervalMinutes(context) }

    var useCustom by remember { mutableStateOf(baby.hasFeedingReminderOverride()) }
    var enabled by remember { mutableStateOf(baby.feedingReminderEnabled ?: globalEnabled) }
    var intervalMinutes by remember {
        mutableIntStateOf(baby.feedingReminderIntervalMinutes ?: globalInterval)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.baby_reminder_title, baby.name)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(R.string.baby_reminder_use_custom),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(
                                if (useCustom) R.string.baby_reminder_custom_on
                                else R.string.baby_reminder_custom_off,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useCustom, onCheckedChange = { useCustom = it })
                }
                if (useCustom) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_feeding_reminders),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    if (enabled) {
                        FieldLabel(stringResource(R.string.settings_feeding_interval_label))
                        ChoiceChips(
                            options = FeedingReminderSettings.INTERVAL_CHOICES,
                            selected = intervalMinutes,
                            onSelect = { intervalMinutes = it },
                            labelOf = { formatDuration(context, it * 60_000L) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (useCustom) onSave(enabled, intervalMinutes) else onSave(null, null)
            }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
