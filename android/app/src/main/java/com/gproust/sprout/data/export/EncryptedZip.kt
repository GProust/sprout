package com.gproust.sprout.data.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.LocalDateTime
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A zip archive encrypted with AES-256, in the WinZip AE-2 scheme.
 *
 * This exists because a report can end up sitting in a chat thread or an inbox
 * for years (BDR-0013), and the only thing that helps there is real encryption
 * of the file itself.
 *
 * **The legacy ZipCrypto scheme is deliberately not implemented.** It is the one
 * Windows Explorer and macOS can open by double-click, which makes it tempting,
 * and it is broken: a known-plaintext attack recovers the contents in seconds,
 * and an archive of a PDF and an `.xlsx` hands an attacker exactly the known
 * plaintext it needs — both formats begin with fixed bytes. Shipping it on a
 * baby's health record would be theatre. The cost of the sound choice is real
 * and is stated in the app: the recipient may need a proper unzip tool.
 *
 * No dependency, per ADR-0013 — every primitive is in `javax.crypto`, which
 * partner sync already relies on. The one thing not taken from there is the key
 * derivation: `PBKDF2WithHmacSHA1` has historically disagreed between JCE
 * providers on how a password's characters become bytes, and a disagreement
 * there produces an archive that simply will not open. So PBKDF2 is written out
 * below over an explicit UTF-8 encoding — the same one 7-Zip and WinZip use —
 * and checked against the RFC 6070 vectors.
 *
 * ## The format, for whoever reads this next
 *
 * Per entry, the encrypted payload is
 * `salt (16) | password verification (2) | ciphertext | authentication code (10)`,
 * and the entry's headers carry compression method 99 with an extra field
 * (`0x9901`) naming AES-256 and the real compression method underneath. AE-2
 * stores no CRC — the field is zero, and the HMAC is what proves the bytes
 * arrived intact.
 */
object EncryptedZip {

    const val MIME_TYPE = "application/zip"
    const val EXTENSION = "zip"

    /** One file to put in the archive. */
    class Entry(val name: String, val bytes: ByteArray)

    // AES-256: a 16-byte salt, two 32-byte keys and two verification bytes.
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32
    private const val VERIFIER_BYTES = 2
    private const val AUTH_CODE_BYTES = 10
    private const val ITERATIONS = 1000

    private const val AES_STRENGTH_256 = 3
    private const val METHOD_AES = 99
    private const val METHOD_STORED = 0
    private const val VERSION_AES = 51
    private const val AE_2 = 2

    /**
     * Writes [entries] into [out] as one archive, each encrypted under
     * [password].
     *
     * Nothing is deflated first: a PDF and an `.xlsx` are already compressed,
     * so a second pass would spend time to save almost nothing, and "stored"
     * keeps what happens to the bytes easy to follow.
     *
     * [modifiedAt] and [random] are parameters so a test can pin them; in the
     * app they are the clock and a fresh [SecureRandom].
     */
    fun write(
        entries: List<Entry>,
        password: CharArray,
        out: OutputStream,
        modifiedAt: LocalDateTime = LocalDateTime.now(),
        random: SecureRandom = SecureRandom(),
    ) {
        require(entries.isNotEmpty()) { "an archive needs at least one entry" }
        require(password.isNotEmpty()) { "an encrypted archive needs a password" }

        val passwordBytes = utf8(password)
        val time = dosTime(modifiedAt)
        val date = dosDate(modifiedAt)
        val central = ByteArrayOutputStream()
        var offset = 0

        try {
            for (entry in entries) {
                val name = entry.name.toByteArray(Charsets.UTF_8)
                val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
                val payload = encrypt(entry.bytes, passwordBytes, salt)

                val local = localHeader(name, payload.size, entry.bytes.size, time, date)
                out.write(local)
                out.write(payload)

                central.write(
                    centralHeader(name, payload.size, entry.bytes.size, time, date, offset),
                )
                offset += local.size + payload.size
            }
        } finally {
            passwordBytes.fill(0)
        }

        val directory = central.toByteArray()
        out.write(directory)
        out.write(endOfCentralDirectory(entries.size, directory.size, offset))
        out.flush()
    }

    // --- the encrypted payload -------------------------------------------

    /**
     * `salt | verification | ciphertext | authentication code`.
     *
     * The two verification bytes are what lets a reader say "wrong password"
     * immediately instead of handing back rubbish; they are the tail of the
     * same derived key material, so they prove knowledge of the password
     * without revealing it.
     */
    private fun encrypt(plain: ByteArray, password: ByteArray, salt: ByteArray): ByteArray {
        val derived = pbkdf2(password, salt, ITERATIONS, KEY_BYTES * 2 + VERIFIER_BYTES)
        val encryptionKey = derived.copyOfRange(0, KEY_BYTES)
        val authenticationKey = derived.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
        val verifier = derived.copyOfRange(KEY_BYTES * 2, derived.size)

        val cipherText = counterMode(plain, encryptionKey)
        val authCode = hmacSha1(authenticationKey, cipherText).copyOf(AUTH_CODE_BYTES)

        derived.fill(0)
        encryptionKey.fill(0)
        authenticationKey.fill(0)

        return salt + verifier + cipherText + authCode
    }

    /**
     * AES in counter mode, with WinZip's counter rather than the usual one.
     *
     * The block counter starts at 1 and is incremented as a **little-endian**
     * integer. `AES/CTR/NoPadding` counts big-endian, so it cannot be used
     * here: it would produce an archive that decrypts to noise everywhere past
     * the first block boundary. The counter blocks are encrypted with AES-ECB
     * and XORed into the data, which is what counter mode is.
     */
    private fun counterMode(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

        val out = ByteArray(data.size)
        val counter = ByteArray(16)
        var block = 0L
        var position = 0
        while (position < data.size) {
            block++
            // Little-endian: the low byte first, which is the whole point.
            var value = block
            for (i in 0 until 8) {
                counter[i] = (value and 0xFF).toByte()
                value = value ushr 8
            }
            val keyStream = cipher.doFinal(counter)
            val remaining = minOf(16, data.size - position)
            for (i in 0 until remaining) {
                out[position + i] = (data[position + i].toInt() xor keyStream[i].toInt()).toByte()
            }
            position += remaining
        }
        return out
    }

    /**
     * PBKDF2-HMAC-SHA1, written out rather than taken from the provider.
     *
     * See the note at the top: providers have disagreed about how a password's
     * characters become bytes, and the failure that causes is an archive nobody
     * can open — discovered by the person on the other end, not by us. Here the
     * password arrives already encoded, and the rest is RFC 2898 §5.2.
     */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(password, "HmacSHA1")) }
        val out = ByteArray(length)
        val blockSize = mac.macLength
        var written = 0
        var blockIndex = 1

        while (written < length) {
            mac.update(salt)
            mac.update(byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            ))
            var u = mac.doFinal()
            val block = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (i in block.indices) block[i] = (block[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(blockSize, length - written)
            block.copyInto(out, written, 0, take)
            written += take
            blockIndex++
        }
        return out
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(data)

    /**
     * The password as UTF-8 bytes — what 7-Zip and WinZip encode, and the
     * reason a password with an accent in it opens on the other side.
     */
    private fun utf8(password: CharArray): ByteArray =
        String(password).toByteArray(Charsets.UTF_8)

    // --- the archive structure -------------------------------------------

    private fun localHeader(
        name: ByteArray,
        compressedSize: Int,
        uncompressedSize: Int,
        time: Int,
        date: Int,
    ) = ByteArrayOutputStream().apply {
        writeInt(0x04034b50)
        writeShort(VERSION_AES)
        writeShort(flags(name))
        writeShort(METHOD_AES)
        writeShort(time)
        writeShort(date)
        writeInt(0) // AE-2 stores no CRC; the HMAC is what proves the bytes.
        writeInt(compressedSize)
        writeInt(uncompressedSize)
        writeShort(name.size)
        writeShort(AES_EXTRA_FIELD.size)
        write(name)
        write(AES_EXTRA_FIELD)
    }.toByteArray()

    private fun centralHeader(
        name: ByteArray,
        compressedSize: Int,
        uncompressedSize: Int,
        time: Int,
        date: Int,
        offset: Int,
    ) = ByteArrayOutputStream().apply {
        writeInt(0x02014b50)
        writeShort(VERSION_AES)
        writeShort(VERSION_AES)
        writeShort(flags(name))
        writeShort(METHOD_AES)
        writeShort(time)
        writeShort(date)
        writeInt(0)
        writeInt(compressedSize)
        writeInt(uncompressedSize)
        writeShort(name.size)
        writeShort(AES_EXTRA_FIELD.size)
        writeShort(0) // no comment
        writeShort(0) // disk 0
        writeShort(0) // internal attributes
        writeInt(0) // external attributes
        writeInt(offset)
        write(name)
        write(AES_EXTRA_FIELD)
    }.toByteArray()

    private fun endOfCentralDirectory(entries: Int, directorySize: Int, directoryOffset: Int) =
        ByteArrayOutputStream().apply {
            writeInt(0x06054b50)
            writeShort(0)
            writeShort(0)
            writeShort(entries)
            writeShort(entries)
            writeInt(directorySize)
            writeInt(directoryOffset)
            writeShort(0) // no archive comment
        }.toByteArray()

    /**
     * Bit 0 says the entry is encrypted. Bit 11 says the name is UTF-8, and is
     * only set when the name actually needs it — an old reader is happier with
     * a plain ASCII name and no flag it does not understand.
     */
    private fun flags(name: ByteArray): Int {
        val ascii = name.all { it in 0x20..0x7E }
        return if (ascii) 0x0001 else 0x0801
    }

    /**
     * The AES extra field: AE-2, AES-256, and the compression that was actually
     * used underneath the encryption.
     */
    private val AES_EXTRA_FIELD: ByteArray = ByteArrayOutputStream().apply {
        writeShort(0x9901)
        writeShort(7)
        writeShort(AE_2)
        write('A'.code)
        write('E'.code)
        write(AES_STRENGTH_256)
        writeShort(METHOD_STORED)
    }.toByteArray()

    private fun dosTime(at: LocalDateTime) =
        (at.hour shl 11) or (at.minute shl 5) or (at.second / 2)

    private fun dosDate(at: LocalDateTime) =
        ((at.year - 1980).coerceAtLeast(0) shl 9) or (at.monthValue shl 5) or at.dayOfMonth

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
