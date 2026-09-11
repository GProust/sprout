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
    enum class Unavailable { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, TOO_OLD, RADIO_REFUSED }

    /**
     * The radio said no once the window had already opened — advertising or
     * scanning refused to start.
     *
     * Its own signal rather than an empty list, because the two are not the
     * same thing to a parent standing next to the phone they are trying to
     * reach: "nobody answered" invites them to wait, and this asks them to fix
     * something.
     */
    class RadioRefused(message: String) : Exception(message)

    /** Null when a window can be opened right now. */
    fun unavailableReason(): Unavailable?

    /**
     * Advertises this household, listens for it, and exchanges with whoever
     * answers before the window closes.
     *
     * @param beacon what to advertise — already derived, so a transport never
     * sees the household secret.
     * @param isOurs whether a beacon seen on the air belongs to this household.
     * A predicate rather than a comparison against [beacon], because only the
     * caller can accept the previous half-hour as well as the current one, and
     * two phones meeting either side of a window boundary must still recognise
     * each other.
     * @param mine this phone's sealed replica, handed over as opaque bytes.
     * @return one sealed replica per phone met; empty when nobody answered,
     * which is the ordinary outcome and not an error.
     * @throws RadioRefused when the window could not really be opened.
     */
    suspend fun exchange(
        beacon: ByteArray,
        isOurs: (ByteArray) -> Boolean,
        mine: ByteArray,
        windowMs: Long,
    ): List<ByteArray>
}
