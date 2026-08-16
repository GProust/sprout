package com.gproust.sprout.data.sync.nearby

/**
 * A way of meeting the household's other phones and swapping replicas with
 * them, for the length of one bounded window (ADR-0010).
 *
 * Kept as an interface for one blunt reason: the Bluetooth implementation
 * cannot be tested without two radios, so everything with a decision in it
 * lives on the other side of this boundary, where a fake transport lets CI
 * exercise it.
 */
interface NearbyTransport {

    /** Why a window could not be opened, for the sentence the user is shown. */
    enum class Unavailable { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, TOO_OLD }

    /** Null when a window can be opened right now. */
    fun unavailableReason(): Unavailable?

    /**
     * Advertises this household, listens for it, and exchanges with whoever
     * answers before the window closes.
     *
     * @param beacon what to advertise and to look for — already derived, so a
     * transport never sees the household secret.
     * @param mine this phone's sealed replica, handed over as opaque bytes.
     * @return one sealed replica per phone met; empty when nobody answered,
     * which is the ordinary outcome and not an error.
     */
    suspend fun exchange(beacon: ByteArray, mine: ByteArray, windowMs: Long): List<ByteArray>
}
