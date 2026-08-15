package com.gproust.sprout.data.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The secret two paired phones share, and the only thing that can open a
 * replica (ADR-0008).
 *
 * It is generated once, on the phone that starts the pairing, and travels to
 * the other in the invitation — so it is worth as much as the data it protects,
 * and is stored encrypted at rest rather than in plain preferences.
 */
class SyncSecret(val bytes: ByteArray) {

    init {
        require(bytes.size == SIZE_BYTES) { "a sync secret is $SIZE_BYTES bytes, not ${bytes.size}" }
    }

    /**
     * Six characters both phones can show after pairing, for the parents to read
     * to each other — the moment that turns "a file arrived" into "we are paired
     * with *each other*".
     *
     * Derived from the secret through SHA-256, so seeing the code tells an
     * onlooker nothing about the secret itself. Ambiguous characters (0/O, 1/I)
     * are left out of the alphabet: this gets read aloud at 3 a.m.
     */
    fun verificationCode(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return (0 until CODE_LENGTH)
            .map { CODE_ALPHABET[(digest[it].toInt() and 0xFF) % CODE_ALPHABET.length] }
            .joinToString("")
    }

    companion object {
        const val SIZE_BYTES = 32
        private const val CODE_LENGTH = 6
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun random(): SyncSecret = SyncSecret(ByteArray(SIZE_BYTES).also(SecureRandom()::nextBytes))
    }
}

/** Raised when a replica cannot be opened with the secret this phone holds. */
class SyncCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Wraps a payload for the journey between two phones.
 *
 * Phase 1 sends replicas through whatever channel the user picks — a messaging
 * app, Quick Share, Bluetooth — so what leaves the phone is an opaque blob that
 * only the paired device can open, and that cannot be altered on the way
 * without the tag failing.
 *
 * The wire format, versioned from the first byte so an older Sprout can refuse
 * a newer file rather than mangle it:
 *
 * ```
 * "SPRT" | version (1 byte) | nonce (12 bytes) | AES-256-GCM ciphertext + tag
 * ```
 *
 * The header is authenticated as associated data, so the version cannot be
 * rewritten to talk an older reader into a different interpretation.
 */
object SyncCrypto {

    private const val MAGIC = "SPRT"
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val headerSize = MAGIC.length + 1

    /** Compresses, encrypts and frames [plaintext]. */
    fun seal(plaintext: ByteArray, secret: SyncSecret): ByteArray {
        val header = header()
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }
        val body = cipher.doFinal(gzip(plaintext))
        return header + nonce + body
    }

    /**
     * Unwraps what [seal] produced.
     *
     * @throws SyncCryptoException if this is not a Sprout replica, was written
     * by a newer version, was damaged, or was not sealed with [secret] — which
     * from the outside all look the same, and are told apart for the user's
     * sake, not the attacker's.
     */
    fun open(sealed: ByteArray, secret: SyncSecret): ByteArray {
        if (sealed.size < headerSize + NONCE_BYTES) {
            throw SyncCryptoException("not a Sprout replica: too short")
        }
        val header = sealed.copyOfRange(0, headerSize)
        if (String(header, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) {
            throw SyncCryptoException("not a Sprout replica")
        }
        if (header[MAGIC.length] != VERSION) {
            throw SyncCryptoException("replica version ${header[MAGIC.length]} is newer than this app")
        }
        val nonce = sealed.copyOfRange(headerSize, headerSize + NONCE_BYTES)
        val body = sealed.copyOfRange(headerSize + NONCE_BYTES, sealed.size)
        val plain = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(header)
                doFinal(body)
            }
        } catch (e: Exception) {
            // AEAD cannot distinguish "wrong key" from "altered bytes", and the
            // honest message covers both: this file is not one your phone can open.
            throw SyncCryptoException("this replica was not sent by your paired phone, or was damaged", e)
        }
        return try {
            gunzip(plain)
        } catch (e: Exception) {
            throw SyncCryptoException("damaged replica", e)
        }
    }

    private fun header(): ByteArray = MAGIC.toByteArray(Charsets.US_ASCII) + VERSION

    private fun key(secret: SyncSecret) = SecretKeySpec(secret.bytes, "AES")

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
