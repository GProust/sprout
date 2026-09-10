package com.gproust.sprout.data.sync.nearby

import android.content.Context
import androidx.core.content.edit
import com.gproust.sprout.data.sync.DeviceIdentity
import com.gproust.sprout.data.sync.HouseholdDevices
import com.gproust.sprout.data.sync.MergeSummary
import com.gproust.sprout.data.sync.PairingStore
import com.gproust.sprout.data.sync.SyncCrypto
import com.gproust.sprout.data.sync.SyncEngine
import com.gproust.sprout.data.sync.SyncPayloadCodec

/** What one discovery window came to. */
sealed interface NearbyResult {
    /** Met at least one phone; [summary] is everything that arrived, merged. */
    data class Synced(val summary: MergeSummary, val phones: Int) : NearbyResult

    /** The window opened and nobody answered — the ordinary outcome, not a failure. */
    data object NobodyNearby : NearbyResult

    /** Throttled: a window opened recently, so this one was not worth the radio. */
    data object TooSoon : NearbyResult

    data object NotPaired : NearbyResult

    /**
     * Both phones tracked before pairing, so the first merge has a question to
     * ask that a window cannot ask (ADR-0008). Automatic sync stands aside and
     * points at the screen, rather than answering on the parent's behalf.
     */
    data object FirstMergeNeedsYou : NearbyResult

    data class CannotStart(val reason: NearbyTransport.Unavailable) : NearbyResult
}

/**
 * One discovery window, from deciding whether to open it to merging what came
 * back (ADR-0010).
 *
 * This is where the policy, the crypto and the merge meet, and it is
 * deliberately ignorant of Bluetooth — the transport is an interface, so all of
 * it can be tested with a fake one.
 */
class NearbySync(
    private val context: Context,
    private val engine: SyncEngine,
    private val pairingStore: PairingStore,
    private val householdDevices: HouseholdDevices,
    private val transport: NearbyTransport,
    private val deviceName: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Opens a window if the policy allows, and merges whatever comes back.
     *
     * @param userAsked true when the parent tapped *Sync now*, which is never
     * throttled.
     */
    suspend fun run(userAsked: Boolean): NearbyResult {
        val pairing = pairingStore.current() ?: return NearbyResult.NotPaired
        if (!NearbyPolicy.mayOpenWindow(lastOpenedAt(), now(), userAsked)) return NearbyResult.TooSoon
        transport.unavailableReason()?.let { return NearbyResult.CannotStart(it) }

        // Recorded before the window rather than after, so a crash or a phone
        // put to sleep mid-exchange cannot turn into a radio that reopens on
        // every launch.
        rememberWindowOpened(now())

        val mine = SyncCrypto.seal(
            SyncPayloadCodec.encode(
                engine.buildPayload(
                    householdId = pairing.householdId,
                    deviceId = DeviceIdentity.id(context),
                    deviceName = deviceName(),
                    includePumping = pairing.shareStash,
                ),
            ),
            pairing.secret,
        )

        val replies = try {
            transport.exchange(
                beacon = HouseholdBeacon.value(pairing.secret, now()),
                // The secret stays here; the transport only ever gets an answer.
                isOurs = { HouseholdBeacon.matches(it, pairing.secret, now()) },
                mine = mine,
                windowMs = NearbyPolicy.WINDOW_MS,
            )
        } catch (e: NearbyTransport.RadioRefused) {
            return NearbyResult.CannotStart(NearbyTransport.Unavailable.RADIO_REFUSED)
        }
        if (replies.isEmpty()) return NearbyResult.NobodyNearby

        var summary = MergeSummary()
        var merged = 0
        for (reply in replies) {
            // One phone in the household sending something unreadable must not
            // cost us what the others sent, so each is taken on its own.
            val payload = runCatching {
                SyncPayloadCodec.decode(SyncCrypto.open(reply, pairing.secret), engine.schemaVersion())
            }.getOrNull() ?: continue
            if (payload.householdId != pairing.householdId) continue

            // The very first merge between two phones that both have history is
            // the one moment with a question attached — keep everything, or
            // share from the pairing forward. A window that answered it silently
            // would be choosing for someone.
            if (!pairingStore.firstMergeDone() &&
                engine.hasOwnHistory() &&
                payload.babies.any { it.deletedAt == null }
            ) {
                return NearbyResult.FirstMergeNeedsYou
            }

            summary += engine.merge(payload)
            householdDevices.seen(payload.deviceId, payload.deviceName, now())
            merged++
        }
        if (merged == 0) return NearbyResult.NobodyNearby
        pairingStore.markFirstMergeDone()
        return NearbyResult.Synced(summary, merged)
    }

    private fun lastOpenedAt(): Long? =
        prefs().getLong(KEY_LAST_WINDOW, 0L).takeIf { it > 0L }

    private fun rememberWindowOpened(at: Long) = prefs().edit { putLong(KEY_LAST_WINDOW, at) }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "settings"
        const val KEY_LAST_WINDOW = "sync_last_nearby_window"
    }
}
