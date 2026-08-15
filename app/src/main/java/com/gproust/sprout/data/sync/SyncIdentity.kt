package com.gproust.sprout.data.sync

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * The identity primitives partner sync is built on (ADR-0007, in `docs/adr/`).
 *
 * Nothing here talks to another device — that is phase 1. Phase 0 only makes the
 * data *mergeable*, which means every row needs a name that is stable across
 * phones, and this phone needs a name of its own.
 */

/**
 * A fresh identifier for a row, unique across devices and never reused.
 *
 * The local `id: Long` stays the primary key — Room relations and every existing
 * query keep working — but it is a per-device counter: feeding `#7` here and
 * feeding `#7` on the partner's phone are unrelated. The [uid][newUid] is what
 * says "the same entry" when two replicas meet.
 */
fun newUid(): String = UUID.randomUUID().toString()

/**
 * How long a tombstone is kept before compaction erases it for good.
 *
 * Long enough that a partner who has not synced in a season still learns about
 * the deletion rather than resurrecting the row; short enough that deleted data
 * does not linger on the device forever.
 */
const val TOMBSTONE_RETENTION_DAYS: Long = 180

/** [TOMBSTONE_RETENTION_DAYS] in millis. */
const val TOMBSTONE_RETENTION_MS: Long = TOMBSTONE_RETENTION_DAYS * 24 * 60 * 60 * 1000

/**
 * This phone's own identifier, generated once on first use and kept for the life
 * of the install.
 *
 * It is deliberately **not** in the database: it identifies the device, so it
 * must not travel inside a replica the way a synced row does. It lives in the
 * same SharedPreferences file as the other device-local settings, and it is
 * meaningless to anyone but the paired phone — a random UUID, tied to no
 * account and to nothing about the hardware.
 */
object DeviceIdentity {
    private const val PREFS = "settings"
    private const val KEY_DEVICE_ID = "device_id"

    /** This device's id, creating and persisting one the first time it is asked for. */
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        return newUid().also { fresh -> prefs.edit { putString(KEY_DEVICE_ID, fresh) } }
    }
}
