package com.gproust.sprout.data.sync

import android.util.Base64
import org.json.JSONObject

/**
 * The thing one parent sends the other to pair (ADR-0008).
 *
 * It travels the same way the data will — Quick Share, a messaging app,
 * Bluetooth — which is what keeps phase 1 free of a camera permission, and is
 * also the one moment where the secret is only as private as the channel the
 * user picked. Two things narrow that window:
 *
 * - it **expires**, so an invitation found in an old conversation pairs nobody;
 * - it is **accepted once**, and the phone that accepted it shows a
 *   verification code its partner can check.
 *
 * Deliberately *not* encrypted: there is no shared key yet — that is the whole
 * point of it — so pretending otherwise would only be theatre.
 */
data class SyncInvitation(
    val householdId: String,
    val secret: SyncSecret,
    val createdAt: Long,
    /** Who is inviting, so the other phone can say "paired with Alex's phone". */
    val fromName: String,
) {
    fun isExpired(now: Long): Boolean = now - createdAt > VALID_FOR_MS

    companion object {
        /**
         * Long enough for "I'll send it when I'm home", short enough that a
         * stale invitation in a chat history is worthless.
         */
        const val VALID_FOR_MS: Long = 24 * 60 * 60 * 1000

        const val FORMAT_VERSION = 1
    }
}

/** Raised when an invitation cannot be used, with the reason the UI has to explain. */
sealed class SyncInvitationException(message: String) : Exception(message) {
    class Unreadable : SyncInvitationException("this file is not a Sprout invitation")
    class TooNew(val formatVersion: Int) : SyncInvitationException("invitation written by a newer Sprout")
    class Expired : SyncInvitationException("this invitation has expired")
}

object SyncInvitationCodec {

    fun encode(invitation: SyncInvitation): ByteArray = JSONObject().apply {
        put("formatVersion", SyncInvitation.FORMAT_VERSION)
        put("householdId", invitation.householdId)
        put("secret", Base64.encodeToString(invitation.secret.bytes, Base64.NO_WRAP))
        put("createdAt", invitation.createdAt)
        put("fromName", invitation.fromName)
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * @throws SyncInvitationException.Unreadable if this isn't an invitation.
     * @throws SyncInvitationException.TooNew if a newer Sprout wrote it.
     * @throws SyncInvitationException.Expired if it is past its window.
     */
    fun decode(bytes: ByteArray, now: Long): SyncInvitation {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SyncInvitationException.Unreadable()
        }
        val formatVersion = root.optInt("formatVersion", -1)
        if (formatVersion < 0) throw SyncInvitationException.Unreadable()
        if (formatVersion > SyncInvitation.FORMAT_VERSION) {
            throw SyncInvitationException.TooNew(formatVersion)
        }
        val invitation = try {
            SyncInvitation(
                householdId = root.getString("householdId"),
                secret = SyncSecret(Base64.decode(root.getString("secret"), Base64.NO_WRAP)),
                createdAt = root.getLong("createdAt"),
                fromName = root.optString("fromName"),
            )
        } catch (e: Exception) {
            throw SyncInvitationException.Unreadable()
        }
        if (invitation.isExpired(now)) throw SyncInvitationException.Expired()
        return invitation
    }
}
