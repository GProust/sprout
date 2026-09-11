package com.gproust.sprout.ui.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.sync.DeviceIdentity
import com.gproust.sprout.data.sync.HouseholdDevice
import com.gproust.sprout.data.sync.HouseholdDevices
import com.gproust.sprout.data.sync.MergeSummary
import com.gproust.sprout.data.sync.Pairing
import com.gproust.sprout.data.sync.PairingStore
import com.gproust.sprout.data.sync.SyncCrypto
import com.gproust.sprout.data.sync.SyncCryptoException
import com.gproust.sprout.data.sync.SyncEngine
import com.gproust.sprout.data.sync.SyncFiles
import com.gproust.sprout.data.sync.SyncInvitation
import com.gproust.sprout.data.sync.SyncInvitationCodec
import com.gproust.sprout.data.sync.SyncInvitationException
import com.gproust.sprout.data.sync.SyncPayloadCodec
import com.gproust.sprout.data.sync.SyncPayloadException
import com.gproust.sprout.data.sync.SyncSecret
import com.gproust.sprout.data.sync.nearby.BluetoothNearbyTransport
import com.gproust.sprout.data.sync.nearby.NearbyPermissions
import com.gproust.sprout.data.sync.nearby.NearbyResult
import com.gproust.sprout.data.sync.nearby.NearbySettings
import com.gproust.sprout.data.sync.nearby.NearbySync
import com.gproust.sprout.data.sync.nearby.NearbyTransport
import com.gproust.sprout.data.sync.newUid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the sync screen is showing. */
data class SyncUiState(
    val pairing: Pairing? = null,
    /** The other phones this one has heard from (ADR-0009). */
    val devices: List<HouseholdDevice> = emptyList(),
    val busy: Boolean = false,
    /** Whether this phone looks for the household when the app opens (ADR-0010). */
    val nearbyEnabled: Boolean = false,
    /** False on phones too old to be offered it at all — they keep the manual exchange. */
    val nearbySupported: Boolean = true,
    /** A file to hand to the share sheet; consumed once shared. */
    val fileToShare: ShareRequest? = null,
    val outcome: SyncOutcome? = null,
)

data class ShareRequest(val uri: Uri, val isInvitation: Boolean)

/** The end of an exchange, as something to put in front of the user. */
sealed interface SyncOutcome {
    /** Pairing succeeded; [code] is what both parents should see. */
    data class Paired(val partnerName: String, val code: String) : SyncOutcome

    data class Merged(val summary: MergeSummary) : SyncOutcome

    /**
     * A device was removed, so the household's secret changed. Everyone who
     * stays needs a fresh invitation — including anyone who was simply away
     * (ADR-0009).
     */
    data class SecretRotated(val code: String) : SyncOutcome

    /**
     * Both phones tracked before pairing, so their entries cannot be matched up
     * (ADR-0008). The parent decides, once, what the first merge should do.
     */
    data class AskAboutHistories(val uri: Uri) : SyncOutcome

    data class Failed(@param:StringRes val message: Int) : SyncOutcome

    /** A discovery window closed without meeting anyone — ordinary, and worth saying. */
    data object NobodyNearby : SyncOutcome
}

/**
 * Pairing with the household's other phones, and exchanging replicas with them.
 *
 * Two ways in, one merge. By hand — a file is written when the parents ask to
 * send one, and read when they hand one over — and by Bluetooth, in a window
 * that lasts ten seconds and only ever opens while the app is in front of
 * someone (ADR-0010). Nothing polls, nothing runs in the background, and
 * nothing here touches the network: the app has no `INTERNET` permission to
 * touch it with.
 */
class SyncViewModel(
    private val repository: SproutRepository,
    private val engine: SyncEngine,
    private val pairingStore: PairingStore,
    private val householdDevices: HouseholdDevices,
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Whether to offer the automatic exchange at all — normally the phone's own
     * answer. Overridable for the screenshot capture, which runs on an emulator
     * older than the API 31 floor and would otherwise photograph a screen no
     * current phone shows.
     */
    nearbySupported: Boolean = NearbyPermissions.isSupported(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        SyncUiState(
            pairing = pairingStore.current(),
            devices = householdDevices.all(),
            nearbyEnabled = NearbySettings.isEnabled(context),
            nearbySupported = nearbySupported,
        ),
    )
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    fun shareHandled() = _state.update { it.copy(fileToShare = null) }

    /**
     * Starts a pairing: a fresh household and a fresh secret, written to a file
     * for the partner to open. This phone is paired the moment it is created —
     * it already knows the secret — so its own data is ready to travel.
     */
    fun createInvitation(shareStash: Boolean) = work {
        val pairing = pairingStore.current() ?: Pairing(
            householdId = newUid(),
            secret = SyncSecret.random(),
            pairedAt = now(),
            shareStash = shareStash,
        ).also(pairingStore::save)

        val invitation = SyncInvitation(
            householdId = pairing.householdId,
            secret = pairing.secret,
            createdAt = now(),
            // So the other phone can say who it just paired with, rather than
            // "an unknown device".
            fromName = repository.parentProfile.first()?.name.orEmpty(),
        )
        val uri = SyncFiles.stage(context, SyncInvitationCodec.encode(invitation), "sprout-invitation")
        _state.update {
            it.copy(pairing = pairing, fileToShare = ShareRequest(uri, isInvitation = true))
        }
    }

    /** This phone's replica, ready to send. */
    fun exportReplica() = work {
        val pairing = pairingStore.current() ?: return@work fail(R.string.sync_error_not_paired)
        val payload = engine.buildPayload(
            householdId = pairing.householdId,
            deviceId = DeviceIdentity.id(context),
            // So the household list on the other phones can name this one.
            deviceName = repository.parentProfile.first()?.name.orEmpty(),
            includePumping = pairing.shareStash,
        )
        val sealed = SyncCrypto.seal(SyncPayloadCodec.encode(payload), pairing.secret)
        val uri = SyncFiles.stage(context, sealed, "sprout-data")
        _state.update { it.copy(fileToShare = ShareRequest(uri, isInvitation = false)) }
    }

    /**
     * Takes in whatever file the user was sent. An invitation and a replica look
     * different from the first byte, so the parents never have to know which of
     * the two they are opening.
     */
    fun open(uri: Uri, historyChoice: HistoryChoice? = null) = work {
        val bytes = runCatching { SyncFiles.read(context, uri) }.getOrElse {
            return@work fail(R.string.sync_error_unreadable)
        }
        if (looksLikeReplica(bytes)) acceptReplica(uri, bytes, historyChoice) else acceptInvitation(bytes)
    }

    fun setShareStash(share: Boolean) {
        pairingStore.setShareStash(share)
        _state.update { it.copy(pairing = pairingStore.current()) }
    }

    fun unpair() {
        pairingStore.unpair()
        householdDevices.clear()
        _state.update { it.copy(pairing = null, devices = emptyList(), outcome = null) }
    }

    /**
     * Removes a phone from the household (ADR-0009).
     *
     * Membership is possession of the secret, so this rotates it: the removed
     * phone can no longer read what comes next, and **every** other phone needs
     * a fresh invitation before it can either. The removed phone keeps what it
     * already has, which no amount of rotation can change.
     */
    fun removeDevice(deviceId: String) = work {
        householdDevices.forget(deviceId)
        val rotated = pairingStore.rotateSecret(now()) ?: return@work fail(R.string.sync_error_not_paired)
        _state.update {
            it.copy(
                pairing = rotated,
                devices = householdDevices.all(),
                outcome = SyncOutcome.SecretRotated(rotated.verificationCode()),
            )
        }
    }

    // --- meeting the household in person (ADR-0010) ------------------------

    /**
     * Turns the automatic exchange on or off.
     *
     * Only records the choice: asking for the Bluetooth permissions belongs to
     * the screen, which is where a dialog can be shown.
     */
    fun setNearbyEnabled(enabled: Boolean) {
        NearbySettings.setEnabled(context, enabled)
        _state.update { it.copy(nearbyEnabled = enabled) }
    }

    /**
     * Opens a discovery window: advertise, look, and merge with whoever is
     * within a few metres and has Sprout open.
     *
     * @param userAsked true for *Sync now*, which is never throttled. On the
     * automatic path a window that is refused, or that meets nobody, says
     * nothing — there is no news in "your partner isn't in the room".
     */
    fun syncNearby(userAsked: Boolean) = work {
        if (!userAsked && !NearbySettings.isEnabled(context)) return@work
        // Spelled out rather than hidden behind a helper: lint only recognises
        // a direct SDK_INT check as a guard for the API-gated transport.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (userAsked) fail(R.string.sync_nearby_error_too_old)
            return@work
        }
        val outcome = NearbySync(
            context = context,
            engine = engine,
            pairingStore = pairingStore,
            householdDevices = householdDevices,
            transport = BluetoothNearbyTransport(context),
            deviceName = { repository.parentProfile.first()?.name.orEmpty() },
            now = now,
        ).run(userAsked)
        when (outcome) {
            is NearbyResult.Synced -> _state.update {
                it.copy(devices = householdDevices.all(), outcome = SyncOutcome.Merged(outcome.summary))
            }
            NearbyResult.NobodyNearby ->
                if (userAsked) _state.update { it.copy(outcome = SyncOutcome.NobodyNearby) }
            NearbyResult.FirstMergeNeedsYou ->
                if (userAsked) fail(R.string.sync_nearby_error_first_merge)
            is NearbyResult.CannotStart ->
                if (userAsked) fail(reasonMessage(outcome.reason))
            // Throttled, or not paired: nothing worth interrupting anyone for.
            NearbyResult.TooSoon, NearbyResult.NotPaired -> Unit
        }
    }

    @StringRes
    private fun reasonMessage(reason: NearbyTransport.Unavailable): Int = when (reason) {
        NearbyTransport.Unavailable.NO_BLUETOOTH -> R.string.sync_nearby_error_no_bluetooth
        NearbyTransport.Unavailable.BLUETOOTH_OFF -> R.string.sync_nearby_error_bluetooth_off
        NearbyTransport.Unavailable.NO_PERMISSION -> R.string.sync_nearby_error_no_permission
        NearbyTransport.Unavailable.TOO_OLD -> R.string.sync_nearby_error_too_old
        NearbyTransport.Unavailable.RADIO_REFUSED -> R.string.sync_nearby_error_radio
    }

    // --- the two kinds of file --------------------------------------------

    private suspend fun acceptInvitation(bytes: ByteArray) {
        val invitation = try {
            SyncInvitationCodec.decode(bytes, now())
        } catch (e: SyncInvitationException) {
            return fail(
                when (e) {
                    is SyncInvitationException.Expired -> R.string.sync_error_invitation_expired
                    is SyncInvitationException.TooNew -> R.string.sync_error_too_new
                    is SyncInvitationException.Unreadable -> R.string.sync_error_unreadable
                },
            )
        }
        val existing = pairingStore.current()
        // Re-invited after a rotation, this is the same pairing with a new key —
        // moving the cut-off would quietly change what "share from the pairing
        // forward" means.
        val rejoined = existing?.takeIf { it.householdId == invitation.householdId }
        val pairing = Pairing(
            householdId = invitation.householdId,
            secret = invitation.secret,
            pairedAt = rejoined?.pairedAt ?: now(),
            shareStash = existing?.shareStash ?: true,
        )
        pairingStore.save(pairing)
        _state.update {
            it.copy(
                pairing = pairing,
                outcome = SyncOutcome.Paired(invitation.fromName, pairing.verificationCode()),
            )
        }
    }

    private suspend fun acceptReplica(uri: Uri, bytes: ByteArray, historyChoice: HistoryChoice?) {
        val pairing = pairingStore.current() ?: return fail(R.string.sync_error_not_paired)
        val plain = try {
            SyncCrypto.open(bytes, pairing.secret)
        } catch (e: SyncCryptoException) {
            return fail(R.string.sync_error_wrong_phone)
        }
        val payload = try {
            SyncPayloadCodec.decode(plain, engine.schemaVersion())
        } catch (e: SyncPayloadException) {
            return fail(
                when (e) {
                    is SyncPayloadException.TooNew -> R.string.sync_error_too_new
                    else -> R.string.sync_error_unreadable
                },
            )
        }
        if (payload.householdId != pairing.householdId) return fail(R.string.sync_error_wrong_phone)

        // The first merge is the only one where the two histories can be
        // irreconcilable, and the only one worth interrupting for (ADR-0008).
        val bothHaveHistory = !pairingStore.firstMergeDone() &&
            engine.hasOwnHistory() &&
            payload.babies.any { it.deletedAt == null }
        if (bothHaveHistory && historyChoice == null) {
            _state.update { it.copy(outcome = SyncOutcome.AskAboutHistories(uri)) }
            return
        }

        val since = if (historyChoice == HistoryChoice.FROM_PAIRING) pairing.pairedAt else null
        val summary = engine.merge(payload, since = since)
        pairingStore.markFirstMergeDone()
        householdDevices.seen(payload.deviceId, payload.deviceName, now())
        selectABabyIfNoneActive()
        _state.update {
            it.copy(devices = householdDevices.all(), outcome = SyncOutcome.Merged(summary))
        }
    }

    /**
     * A phone that just adopted its partner's baby has rows but no *active*
     * baby — the merge writes data, it does not choose what the dashboard
     * shows. Without this, a freshly paired phone opens on an empty screen.
     */
    private suspend fun selectABabyIfNoneActive() {
        if (repository.activeBabyIdNow() != null) return
        repository.activeBabies().firstOrNull()?.let { repository.setActiveBaby(it.id) }
    }

    /** A replica is framed; an invitation is plain JSON. */
    private fun looksLikeReplica(bytes: ByteArray): Boolean =
        bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "SPRT"

    private fun fail(@StringRes message: Int) {
        _state.update { it.copy(outcome = SyncOutcome.Failed(message)) }
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, outcome = null) }
            withContext(Dispatchers.IO) { block() }
            _state.update { it.copy(busy = false) }
        }
    }
}

/** What the first merge should do when both phones tracked before pairing. */
enum class HistoryChoice { KEEP_EVERYTHING, FROM_PAIRING }
