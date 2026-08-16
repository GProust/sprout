package com.gproust.sprout.data.sync.nearby

import com.gproust.sprout.data.sync.SyncSecret
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How two phones of the same household recognise each other over the air
 * (ADR-0010).
 *
 * A phone looking for its household cannot simply advertise the household id:
 * that would be a fixed identifier, broadcast in the clear, that anyone nearby
 * could log and follow from one week to the next — a tracker attached to a
 * family, which is precisely what this app exists not to be.
 *
 * So what goes out is derived: an HMAC of the household secret over the current
 * half-hour, truncated. It changes on its own every window, it is meaningless to
 * anyone without the secret, and a phone that holds the secret can recognise it
 * by computing the same value.
 */
object HouseholdBeacon {

    /**
     * Sprout's own service UUID. Fixed, and public — it says "a Sprout is
     * nearby", which is not a secret. Who that Sprout belongs to is what the
     * rotating value hides.
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
     * Bytes of HMAC kept.
     *
     * A BLE advertisement has 31 bytes in total: 3 for the flags, then 18 of
     * header for a 128-bit service UUID, leaving 10. Eight is comfortably
     * inside that and still 2^64 — collisions between households are not the
     * threat here anyway, since a wrong guess simply fails to connect.
     */
    const val VALUE_BYTES: Int = 8

    /** What to advertise right now. */
    fun value(secret: SyncSecret, at: Long): ByteArray = valueForWindow(secret, at / WINDOW_MS)

    /**
     * Whether an advertisement seen at [at] belongs to this household.
     *
     * Accepts the current window and the one before it: two phones whose clocks
     * differ by a few minutes, or that meet either side of a window boundary,
     * would otherwise fail to recognise each other for no good reason.
     */
    fun matches(observed: ByteArray, secret: SyncSecret, at: Long): Boolean {
        if (observed.size != VALUE_BYTES) return false
        val window = at / WINDOW_MS
        // MessageDigest.isEqual is the constant-time one; a timing oracle here
        // would leak whether a guess is close, which is worth not offering.
        return MessageDigest.isEqual(observed, valueForWindow(secret, window)) ||
            MessageDigest.isEqual(observed, valueForWindow(secret, window - 1))
    }

    private fun valueForWindow(secret: SyncSecret, window: Long): ByteArray {
        val mac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(secret.bytes, ALGORITHM))
        }
        return mac.doFinal(window.toString().toByteArray(Charsets.US_ASCII))
            .copyOf(VALUE_BYTES)
    }

    private const val ALGORITHM = "HmacSHA256"
}
