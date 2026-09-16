package com.gproust.sprout.data.sync.nearby

import com.gproust.sprout.data.sync.SyncSecret
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How two phones of the same household recognise each other over the air
 * (ADR-0010, amended by ADR-0016).
 *
 * A phone looking for its household cannot simply advertise the household id:
 * that would be a fixed identifier, broadcast in the clear, that anyone nearby
 * could log and follow from one week to the next — a tracker attached to a
 * family, which is precisely what this app exists not to be.
 *
 * So what goes out is derived: an HMAC of the household secret over the current
 * half-hour. It changes on its own every window, it is meaningless to anyone
 * without the secret, and a phone that holds the secret can recognise it by
 * computing the same value.
 *
 * There are **two forms of the same idea**, and during the rollout a phone
 * advertises both (ADR-0016):
 *
 * - [value] is the 8-byte beacon, carried as BLE *service data* under
 *   [SERVICE_UUID]. It is what every shipped copy of Sprout looks for, and it
 *   is the one an iPhone can neither send nor be found by.
 * - [advertUuid] is the 128-bit *service UUID* that replaces it. iOS can
 *   advertise a service UUID and nothing else, so this is the only form both
 *   platforms can speak — and it is the more private of the two, because after
 *   the old form is dropped nothing fixed goes out at all.
 */
object HouseholdBeacon {

    /**
     * Sprout's own service UUID, under which the 8-byte beacon travels as
     * service data. Fixed, and public — it says "a Sprout is nearby", which is
     * not a secret. Who that Sprout belongs to is what the rotating value hides.
     *
     * ADR-0016 retires it: [advertUuid] hides the fact of a Sprout too. It stays
     * for one release so that a phone which has not updated is still found.
     */
    val SERVICE_UUID: UUID = UUID.fromString("5f9b3a70-6d1e-4b6a-9c4e-2f7d8a1b0c33")

    /**
     * How long one beacon value lasts.
     *
     * Short enough that the trail an observer could build is worthless; long
     * enough that two phones with ordinary clock drift compute the same value —
     * and a listener accepts the previous window too, so a phone a few minutes
     * behind is still recognised.
     */
    const val WINDOW_MS: Long = 30 * 60 * 1000

    /**
     * Bytes of HMAC kept for the service-data form.
     *
     * A BLE advertisement has 31 bytes in total: 3 for the flags, then 18 of
     * header for a 128-bit service UUID, leaving 10. Eight is comfortably
     * inside that and still 2^64 — collisions between households are not the
     * threat here anyway, since a wrong guess simply fails to connect.
     */
    const val VALUE_BYTES: Int = 8

    /**
     * Label mixed into the derivation of [advertUuid], so the two forms cannot
     * collide even though both are HMACs of the same secret over the same
     * window. Pinned by `spec/vectors/beacon.json`; changing it makes every
     * paired household invisible to itself.
     */
    const val ADVERT_LABEL: String = "sprout-adv-v1:"

    /** Which half-hour [at] falls in. Both forms are derived from this number. */
    fun window(at: Long): Long = at / WINDOW_MS

    /** The 8-byte service-data value to advertise right now. */
    fun value(secret: SyncSecret, at: Long): ByteArray = valueForWindow(secret, window(at))

    /**
     * The service UUID to advertise right now, which is what an iPhone scans
     * for (ADR-0016).
     *
     * Raw 128 bits, with no RFC-4122 version or variant forced: `java.util.UUID`
     * and `CBUUID` both take an arbitrary value, and spending six bits to look
     * like a v4 buys nothing when a wrong guess simply fails to connect.
     */
    fun advertUuid(secret: SyncSecret, at: Long): UUID = advertUuidForWindow(secret, window(at))

    /**
     * The UUIDs to scan for right now: this window and the previous one, so two
     * phones a few minutes apart — or meeting either side of a boundary — still
     * find each other.
     *
     * The service-data form gets the same tolerance from [matches]; a scan
     * filter cannot express "or the one before", so here it is a list.
     */
    fun advertUuidsToScanFor(secret: SyncSecret, at: Long): List<UUID> {
        val window = window(at)
        return listOf(
            advertUuidForWindow(secret, window),
            advertUuidForWindow(secret, window - 1),
        )
    }

    /**
     * Whether a service-data advertisement seen at [at] belongs to this
     * household.
     *
     * Accepts the current window and the one before it: two phones whose clocks
     * differ by a few minutes, or that meet either side of a window boundary,
     * would otherwise fail to recognise each other for no good reason.
     */
    fun matches(observed: ByteArray, secret: SyncSecret, at: Long): Boolean {
        if (observed.size != VALUE_BYTES) return false
        val window = window(at)
        // MessageDigest.isEqual is the constant-time one; a timing oracle here
        // would leak whether a guess is close, which is worth not offering.
        return MessageDigest.isEqual(observed, valueForWindow(secret, window)) ||
            MessageDigest.isEqual(observed, valueForWindow(secret, window - 1))
    }

    private fun valueForWindow(secret: SyncSecret, window: Long): ByteArray =
        hmac(secret, window.toString()).copyOf(VALUE_BYTES)

    private fun advertUuidForWindow(secret: SyncSecret, window: Long): UUID {
        val bytes = hmac(secret, ADVERT_LABEL + window)
        var high = 0L
        var low = 0L
        for (i in 0 until 8) high = (high shl 8) or (bytes[i].toLong() and 0xFFL)
        for (i in 8 until 16) low = (low shl 8) or (bytes[i].toLong() and 0xFFL)
        return UUID(high, low)
    }

    /**
     * The message is the window written as decimal ASCII — `"1"`, not eight
     * bytes of integer. Both platforms must agree on that or nothing ever
     * matches, which is why `spec/wire-format.md` says it twice.
     */
    private fun hmac(secret: SyncSecret, message: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(secret.bytes, ALGORITHM))
        }
        return mac.doFinal(message.toByteArray(Charsets.US_ASCII))
    }

    private const val ALGORITHM = "HmacSHA256"
}
