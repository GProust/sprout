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
 * must not travel inside a replica the way a synced row does. It is meaningless
 * to anyone but the paired phone — a random UUID, tied to no account and to
 * nothing about the hardware.
 *
 * It also must not travel in an Android *backup*. A parent who restores their
 * record onto a new phone and keeps the old one running — handed to the other
 * parent, or simply not wiped yet — would otherwise have two handsets answering
 * to one id: the household list would show one entry for both, and removing
 * "that phone" would be removing whichever of them the list happened to be
 * describing. So it lives in a preferences file of its own, `device.xml`, which
 * `@xml/backup_rules` and `@xml/data_extraction_rules` exclude from the cloud
 * backup and from the phone-to-phone transfer alike (ADR-0011).
 */
object DeviceIdentity {
    /** Device-local, and excluded from backup — nothing else belongs in here. */
    const val PREFS = "device"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * Where the id lived before ADR-0011 gave it a file of its own: in with the
     * settings, and so in every backup taken up to then.
     */
    private const val LEGACY_PREFS = "settings"

    /** This device's id, creating and persisting one the first time it is asked for. */
    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        // An install that predates the move keeps the id it already has: the
        // other phones in the household know it by that name, and handing it a
        // fresh one would leave a ghost in their lists that nothing can clear.
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val id = legacy.getString(KEY_DEVICE_ID, null) ?: newUid()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        // Out of the backed-up file, so this is the last backup that carries it.
        legacy.edit { remove(KEY_DEVICE_ID) }
        return id
    }
}
