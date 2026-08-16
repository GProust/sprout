package com.gproust.sprout.ui.sync

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.sync.HouseholdDevice
import com.gproust.sprout.data.sync.MergeSummary
import com.gproust.sprout.data.sync.SyncFiles
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.rememberSproutViewModelFactory

/**
 * Sharing one baby's record with everyone looking after them — by hand, through
 * whatever channel they already use (ADR-0007 phase 1, ADR-0008, ADR-0009).
 *
 * Usually that is the other parent; it can equally be a grandparent who has the
 * baby twice a week. There is no account here, and nothing happens on its own:
 * this screen makes files and reads files. What it mostly does is explain,
 * because the honest version of "no server" is that people move the data
 * themselves.
 */
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    /** A file another app just handed to Sprout, if the screen was opened that way. */
    incomingFile: Uri? = null,
    onIncomingFileHandled: () -> Unit = {},
    vm: SyncViewModel = viewModel(factory = rememberSproutViewModelFactory()),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }

    // A file that arrived from another app opens the same way a picked one does.
    LaunchedEffect(incomingFile) {
        incomingFile?.let {
            vm.open(it)
            onIncomingFileHandled()
        }
    }

    // Read in composition, not inside the effect: a Context captured by a
    // coroutine doesn't see a configuration change, so the subject could be
    // left in the language the app started in.
    val invitationSubject = stringResource(R.string.sync_share_invitation_subject)
    val dataSubject = stringResource(R.string.sync_share_data_subject)

    // The share sheet is the whole transport: hand the file over and let the
    // parent choose who carries it.
    LaunchedEffect(state.fileToShare) {
        state.fileToShare?.let { request ->
            val subject = if (request.isInvitation) invitationSubject else dataSubject
            context.startActivity(SyncFiles.shareIntent(request.uri, subject))
            vm.shareHandled()
        }
    }

    Scaffold(topBar = { SproutTopBar(stringResource(R.string.screen_sync), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            val pairing = state.pairing
            if (pairing == null) {
                NotPairedSection(
                    onInvite = { vm.createInvitation(shareStash = true) },
                    onOpenFile = { picker.launch(arrayOf("*/*")) },
                )
            } else {
                PairedSection(
                    code = pairing.verificationCode(),
                    shareStash = pairing.shareStash,
                    devices = state.devices,
                    onSend = { vm.exportReplica() },
                    onReceive = { picker.launch(arrayOf("*/*")) },
                    onInviteAgain = { vm.createInvitation(shareStash = pairing.shareStash) },
                    onShareStash = vm::setShareStash,
                    onRemoveDevice = { vm.removeDevice(it) },
                    onUnpair = vm::unpair,
                )
            }
        }
    }

    state.outcome?.let { outcome ->
        OutcomeDialog(
            outcome = outcome,
            onDismiss = vm::dismissOutcome,
            onHistoryChoice = { uri, choice ->
                vm.dismissOutcome()
                vm.open(uri, choice)
            },
        )
    }
}

@Composable
private fun NotPairedSection(onInvite: () -> Unit, onOpenFile: () -> Unit) {
    Text(stringResource(R.string.sync_intro_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.sync_intro_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Button(onClick = onInvite, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_invite))
    }
    OutlinedButton(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_open_invitation))
    }
    Text(
        stringResource(R.string.sync_channel_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairedSection(
    code: String,
    shareStash: Boolean,
    devices: List<HouseholdDevice>,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onInviteAgain: () -> Unit,
    onShareStash: (Boolean) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    var deviceToRemove by remember { mutableStateOf<HouseholdDevice?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.sync_code_title), style = MaterialTheme.typography.titleSmall)
            Text(
                code,
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 32.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                stringResource(R.string.sync_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_send))
    }
    OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_receive))
    }
    Text(
        stringResource(R.string.sync_exchange_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.sync_stash_title))
            Text(
                stringResource(R.string.sync_stash_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = shareStash, onCheckedChange = onShareStash)
    }
    if (!shareStash) {
        Text(
            stringResource(R.string.sync_stash_off_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // The household's other phones, as they have introduced themselves. A list
    // of who has been heard from — not who is allowed in; the key is what
    // decides that (ADR-0009).
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.sync_devices_title), style = MaterialTheme.typography.titleSmall)
    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.sync_devices_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        devices.forEach { device ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.name.ifBlank { stringResource(R.string.sync_device_unnamed) },
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                TextButton(onClick = { deviceToRemove = device }) {
                    Text(stringResource(R.string.sync_device_remove))
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onInviteAgain) { Text(stringResource(R.string.sync_invite_again)) }
    TextButton(onClick = { confirmUnpair = true }) { Text(stringResource(R.string.sync_unpair)) }

    deviceToRemove?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRemove = null },
            title = { Text(stringResource(R.string.sync_remove_title)) },
            text = { Text(stringResource(R.string.sync_remove_body)) },
            confirmButton = {
                TextButton(onClick = { deviceToRemove = null; onRemoveDevice(device.deviceId) }) {
                    Text(stringResource(R.string.sync_device_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.sync_unpair)) },
            text = { Text(stringResource(R.string.sync_unpair_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; onUnpair() }) {
                    Text(stringResource(R.string.sync_unpair))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun OutcomeDialog(
    outcome: SyncOutcome,
    onDismiss: () -> Unit,
    onHistoryChoice: (Uri, HistoryChoice) -> Unit,
) {
    when (outcome) {
        is SyncOutcome.AskAboutHistories -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.sync_histories_title)) },
            text = { Text(stringResource(R.string.sync_histories_body)) },
            confirmButton = {
                TextButton(onClick = { onHistoryChoice(outcome.uri, HistoryChoice.KEEP_EVERYTHING) }) {
                    Text(stringResource(R.string.sync_histories_keep_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { onHistoryChoice(outcome.uri, HistoryChoice.FROM_PAIRING) }) {
                    Text(stringResource(R.string.sync_histories_from_pairing))
                }
            },
        )

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(outcomeTitle(outcome)) },
            text = { Text(outcomeBody(outcome)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun outcomeTitle(outcome: SyncOutcome): String = when (outcome) {
    is SyncOutcome.Paired -> stringResource(R.string.sync_paired_title)
    is SyncOutcome.Merged -> stringResource(R.string.sync_merged_title)
    is SyncOutcome.Failed -> stringResource(R.string.sync_failed_title)
    is SyncOutcome.SecretRotated -> stringResource(R.string.sync_rotated_title)
    is SyncOutcome.AskAboutHistories -> ""
}

@Composable
private fun outcomeBody(outcome: SyncOutcome): String = when (outcome) {
    is SyncOutcome.Paired -> if (outcome.partnerName.isBlank()) {
        stringResource(R.string.sync_paired_body, outcome.code)
    } else {
        stringResource(R.string.sync_paired_body_named, outcome.partnerName, outcome.code)
    }

    is SyncOutcome.Merged -> mergeSummaryText(outcome.summary)
    is SyncOutcome.Failed -> stringResource(outcome.message)
    is SyncOutcome.SecretRotated -> stringResource(R.string.sync_rotated_body, outcome.code)
    is SyncOutcome.AskAboutHistories -> ""
}

/**
 * What the merge did, in a sentence.
 *
 * "Nothing new" is a real and frequent outcome — the parents exchange files more
 * often than they both log — and saying it plainly is what distinguishes a merge
 * that worked from one that silently failed.
 */
@Composable
fun mergeSummaryText(summary: MergeSummary): String {
    if (!summary.changed) return stringResource(R.string.sync_merged_nothing_new)
    val parts = buildList {
        if (summary.added > 0) {
            add(pluralStringResource(R.plurals.sync_merged_added, summary.added, summary.added))
        }
        if (summary.updated > 0) {
            add(pluralStringResource(R.plurals.sync_merged_updated, summary.updated, summary.updated))
        }
        if (summary.deleted > 0) {
            add(pluralStringResource(R.plurals.sync_merged_deleted, summary.deleted, summary.deleted))
        }
    }
    return parts.joinToString(", ")
}
