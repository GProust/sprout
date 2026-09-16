package com.gproust.sprout.data.sync.nearby

import java.util.UUID

/**
 * How a phone tells another which L2CAP channel to dial (ADR-0016).
 *
 * An L2CAP connection-oriented channel is reached by its **PSM**, a number the
 * Bluetooth stack picks when the listener opens its socket. RFCOMM needed none
 * of this — a service record carries a fixed UUID and the stack looks the
 * channel up — but L2CAP has no such directory, so the number has to be
 * published somewhere. ADR-0010 said as much when it rejected L2CAP the first
 * time, and ADR-0016 accepts the cost: a single read-only GATT characteristic
 * on the service the listener advertises.
 *
 * Nothing fixed reaches the air because of it. A GATT service is only visible
 * to a phone that has already connected, and connecting means having matched
 * the rotating [HouseholdBeacon.advertUuid] first.
 */
object L2capPsm {

    /**
     * The characteristic that answers "which PSM?".
     *
     * Fixed on both platforms and pinned by `spec/vectors/l2cap.json`: two apps
     * that derived the same advertisement but read different characteristics
     * would connect and then find nothing, which is the silent failure `spec/`
     * exists to prevent.
     */
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("5f9b3a70-6d1e-4b6a-9c4e-2f7d8a1b0c34")

    /** The value's length: an unsigned 16-bit integer, big-endian. */
    const val BYTES: Int = 2

    /** The largest PSM the two bytes can carry. */
    const val MAX: Int = 0xFFFF

    /** The characteristic's value for a socket listening on [psm]. */
    fun encode(psm: Int): ByteArray {
        require(psm in 1..MAX) { "a PSM is a 16-bit number, not $psm" }
        return byteArrayOf(((psm shr 8) and 0xFF).toByte(), (psm and 0xFF).toByte())
    }

    /**
     * Reads a characteristic's value back.
     *
     * Null rather than an exception for everything unusable — absent, the wrong
     * length, or zero: this is bytes from another phone, and the only sensible
     * answer to "that is not a PSM" is to leave that phone for the next window
     * rather than to fail the whole exchange.
     */
    fun decode(value: ByteArray?): Int? {
        if (value == null || value.size != BYTES) return null
        val psm = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        return psm.takeIf { it > 0 }
    }
}
