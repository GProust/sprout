package com.gproust.sprout.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a secret unreadable at rest.
 *
 * The shared sync secret cannot simply *be* a Keystore key: both phones need the
 * same one, so it has to be exportable into an invitation. What the Keystore
 * protects instead is the copy sitting in this app's preferences — wrapped by a
 * hardware-backed key that never leaves the device.
 */
interface SecretVault {
    fun wrap(bytes: ByteArray): String
    fun unwrap(blob: String): ByteArray
}

/** The real one: an AES key in the Android Keystore, generated on first use. */
class KeystoreVault(private val alias: String = DEFAULT_ALIAS) : SecretVault {

    override fun wrap(bytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(bytes)
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    override fun unwrap(blob: String): ByteArray {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_BYTES)
        val body = raw.copyOfRange(IV_BYTES, raw.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            doFinal(body)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "sprout.sync.vault"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/** Who this phone is paired with, and on what terms (ADR-0008). */
data class Pairing(
    val householdId: String,
    val secret: SyncSecret,
    /** When the pairing was made — the cut-off for "share from the pairing forward". */
    val pairedAt: Long,
    /** The stash switch: whether *this* phone sends its expressed-milk log. */
    val shareStash: Boolean,
) {
    /** The six characters both phones show, for the parents to read to each other. */
    fun verificationCode(): String = secret.verificationCode()
}

/**
 * The pairing, as this phone remembers it.
 *
 * Device-local by construction: it lives in preferences, never in the database,
 * so it cannot accidentally travel inside a replica.
 */
class PairingStore(
    private val context: Context,
    private val vault: SecretVault = KeystoreVault(),
) {

    fun current(): Pairing? {
        val prefs = prefs()
        val householdId = prefs.getString(KEY_HOUSEHOLD, null) ?: return null
        val wrapped = prefs.getString(KEY_SECRET, null) ?: return null
        val secret = runCatching { SyncSecret(vault.unwrap(wrapped)) }.getOrNull() ?: return null
        return Pairing(
            householdId = householdId,
            secret = secret,
            pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L),
            shareStash = prefs.getBoolean(KEY_SHARE_STASH, true),
        )
    }

    fun isPaired(): Boolean = current() != null

    fun save(pairing: Pairing) {
        prefs().edit {
            putString(KEY_HOUSEHOLD, pairing.householdId)
            putString(KEY_SECRET, vault.wrap(pairing.secret.bytes))
            putLong(KEY_PAIRED_AT, pairing.pairedAt)
            putBoolean(KEY_SHARE_STASH, pairing.shareStash)
        }
    }

    /**
     * Replaces the household's secret, keeping the household itself (ADR-0009).
     *
     * This is how a device is removed: possession of the secret *is* membership,
     * so the only way to take it away is to change it and re-invite everyone who
     * stays. Every other phone goes mute until it is re-invited — including one
     * that was merely switched off at the wrong moment — and the phone being
     * removed keeps whatever it already holds. Rotation stops what comes next;
     * it cannot reach backwards.
     *
     * The verification code changes with the secret, which is the visible signal:
     * anyone still showing the old code has not been re-invited yet.
     */
    fun rotateSecret(now: Long): Pairing? {
        val current = current() ?: return null
        val rotated = current.copy(secret = SyncSecret.random(), pairedAt = now)
        save(rotated)
        return rotated
    }

    /**
     * The stash switch (ADR-0008). Send-side only, and prospective: turning it
     * off stops future replicas carrying the stash, it cannot reach into the
     * other phone and take back what was already sent.
     */
    fun setShareStash(share: Boolean) = prefs().edit { putBoolean(KEY_SHARE_STASH, share) }

    /**
     * Forget the partner. The data that already merged stays — it is the
     * household's record, not a lease — and this phone simply stops being able
     * to open or produce replicas for it.
     */
    fun unpair() = prefs().edit {
        remove(KEY_HOUSEHOLD)
        remove(KEY_SECRET)
        remove(KEY_PAIRED_AT)
        remove(KEY_SHARE_STASH)
        remove(KEY_FIRST_MERGE_DONE)
    }

    /** Whether a replica has ever been merged — the first one asks an extra question. */
    fun firstMergeDone(): Boolean = prefs().getBoolean(KEY_FIRST_MERGE_DONE, false)

    fun markFirstMergeDone() = prefs().edit { putBoolean(KEY_FIRST_MERGE_DONE, true) }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "settings"
        const val KEY_HOUSEHOLD = "sync_household_id"
        const val KEY_SECRET = "sync_secret"
        const val KEY_PAIRED_AT = "sync_paired_at"
        const val KEY_SHARE_STASH = "sync_share_stash"
        const val KEY_FIRST_MERGE_DONE = "sync_first_merge_done"
    }
}
