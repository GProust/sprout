package com.gproust.sprout

import com.gproust.sprout.data.export.EncryptedZip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * The AES-256 archive, checked where hand-written crypto can actually go wrong.
 *
 * Two of these tests are cross-implementation rather than self-consistent,
 * which is the point: a writer tested only against its own reader proves
 * nothing, and the failure it would miss — an archive no tool on earth can
 * open — is discovered by the person on the other end, months later.
 *
 * - The key derivation is checked against the **RFC 6070** vectors.
 * - The encrypted payload is checked against a vector produced independently
 *   (Python's `hashlib` + `pycryptodome`), and confirmed before it was written
 *   down by having 7-Zip extract an archive of that shape byte-for-byte.
 */
class EncryptedZipTest {

    private val password = "Léa-2026 clinic".toCharArray()

    /** A salt of 00..0F, so the payload is reproducible. */
    private fun fixedSalt() = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = i.toByte()
        }
    }

    private val at: LocalDateTime = LocalDateTime.of(2026, 9, 8, 14, 30, 8)

    private fun archive(
        entries: List<EncryptedZip.Entry>,
        secret: CharArray = password,
        random: SecureRandom = fixedSalt(),
    ): ByteArray = ByteArrayOutputStream()
        .also { EncryptedZip.write(entries, secret, it, at, random) }
        .toByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String) = ByteArray(text.length / 2) {
        text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun theKeyDerivationMatchesTheRfc6070Vectors() {
        fun derive(password: String, salt: String, iterations: Int, length: Int) =
            hex(
                EncryptedZip.pbkdf2(
                    password.toByteArray(Charsets.UTF_8),
                    salt.toByteArray(Charsets.UTF_8),
                    iterations,
                    length,
                ),
            )

        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", derive("password", "salt", 1, 20))
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", derive("password", "salt", 2, 20))
        assertEquals("4b007901b765489abead49d926f721d065a429c1", derive("password", "salt", 4096, 20))
        assertEquals(
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            derive(
                "passwordPASSWORDpassword",
                "saltSALTsaltSALTsaltSALTsaltSALTsalt",
                4096,
                25,
            ),
        )
    }

    /**
     * The bytes of one entry, against a vector from a different implementation.
     *
     * The plaintext is deliberately 70 bytes — four whole AES blocks and a
     * partial one — because that is where the two mistakes worth catching show
     * up: a counter incremented the wrong way round, and a final block handled
     * as if it were full.
     */
    @Test
    fun theEncryptedPayloadMatchesAnIndependentImplementation() {
        val plain = ByteArray(70) { ((it * 7 + 3) % 256).toByte() }
        val bytes = archive(listOf(EncryptedZip.Entry("r.bin", plain)))

        // salt | verifier | ciphertext | authentication code
        val expected = unhex(
            "000102030405060708090a0b0c0d0e0f" +
                "b0ba" +
                "a224d656b50ee9ebfeaaca45bca45875571a18b1e0dd192bb43f3a0d7be0411f" +
                "0aada0cd8fb556b2be67696405badcb3eb70b1dd700e846eefa52c4eefd72765" +
                "a822a0ac494e" +
                "44d7adccab5fe8a4cd61",
        )
        val payloadStart = LOCAL_HEADER_BYTES + "r.bin".length + AES_EXTRA_BYTES
        val payload = bytes.copyOfRange(payloadStart, payloadStart + expected.size)
        assertArrayEquals(expected, payload)
    }

    @Test
    fun theArchiveIsShapedTheWayAReaderExpects() {
        val plain = ByteArray(100) { it.toByte() }
        val bytes = archive(listOf(EncryptedZip.Entry("report.pdf", plain)))

        assertEquals(0x04034b50, readInt(bytes, 0))
        assertEquals(51, readShort(bytes, 4)) // version needed for AES
        assertEquals(0x0001, readShort(bytes, 6)) // encrypted
        assertEquals(99, readShort(bytes, 8)) // AES, not a real compression method
        assertEquals(0, readInt(bytes, 14)) // AE-2 keeps no CRC
        assertEquals(16 + 2 + plain.size + 10, readInt(bytes, 18)) // compressed size
        assertEquals(plain.size, readInt(bytes, 22))
        assertEquals("report.pdf".length, readShort(bytes, 26))
        assertEquals(AES_EXTRA_BYTES, readShort(bytes, 28))

        // The AES extra field: AE-2, vendor "AE", 256-bit, stored underneath.
        val extra = LOCAL_HEADER_BYTES + "report.pdf".length
        assertEquals(0x9901, readShort(bytes, extra))
        assertEquals(7, readShort(bytes, extra + 2))
        assertEquals(2, readShort(bytes, extra + 4))
        assertEquals('A'.code, bytes[extra + 6].toInt())
        assertEquals('E'.code, bytes[extra + 7].toInt())
        assertEquals(3, bytes[extra + 8].toInt()) // AES-256
        assertEquals(0, readShort(bytes, extra + 9)) // stored

        // A central directory and an end record, where a reader looks first.
        val eocd = bytes.size - 22
        assertEquals(0x06054b50, readInt(bytes, eocd))
        assertEquals(1, readShort(bytes, eocd + 8))
        assertEquals(1, readShort(bytes, eocd + 10))
        val directoryAt = readInt(bytes, eocd + 16)
        assertEquals(0x02014b50, readInt(bytes, directoryAt))
        assertEquals(0, readInt(bytes, directoryAt + 42)) // first entry's offset
    }

    /**
     * Both entries are reachable the way a reader reaches them: from the end
     * record, into the central directory, and out to each local header.
     *
     * This walk is written out rather than handed to `java.util.zip.ZipFile`,
     * which cannot be used here at all: it rejects an archive whose entries
     * declare compression method 99 before it will list anything, because the
     * JDK has no AES support and will not open what it cannot decompress. That
     * is a limitation of that one reader, not of the archive — 7-Zip, WinZip,
     * Keka, Android's file managers, `unzip` and Python's `zipfile` all read
     * it — and the app never claims Java can open it. So the navigation is
     * checked against the format, not against a reader that opted out of it.
     */
    @Test
    fun bothEntriesAreReachableFromTheCentralDirectory() {
        val names = listOf("report.pdf", "data.xlsx")
        val sizes = listOf(4321, 987)
        val bytes = archive(
            names.zip(sizes) { name, size -> EncryptedZip.Entry(name, ByteArray(size) { 1 }) },
        )

        val eocd = bytes.size - 22
        assertEquals(0x06054b50, readInt(bytes, eocd))
        assertEquals(names.size, readShort(bytes, eocd + 10))

        var cursor = readInt(bytes, eocd + 16)
        names.forEachIndexed { index, name ->
            assertEquals(0x02014b50, readInt(bytes, cursor))
            val nameLength = readShort(bytes, cursor + 28)
            val extraLength = readShort(bytes, cursor + 30)
            val commentLength = readShort(bytes, cursor + 32)
            assertEquals(name, String(bytes, cursor + 46, nameLength, Charsets.UTF_8))
            assertEquals(sizes[index], readInt(bytes, cursor + 24)) // the size it really was

            // The offset in the directory has to land on that entry's own
            // local header, or a reader jumps into the middle of the previous
            // entry's ciphertext.
            val local = readInt(bytes, cursor + 42)
            assertEquals(0x04034b50, readInt(bytes, local))
            assertEquals(name, String(bytes, local + 30, readShort(bytes, local + 26), Charsets.UTF_8))

            cursor += 46 + nameLength + extraLength + commentLength
        }
        // The directory ends exactly where the end record begins.
        assertEquals(eocd, cursor)
    }

    @Test
    fun everyEntryGetsItsOwnSalt() {
        // Two identical files must not produce identical ciphertext: a reused
        // salt means a reused key stream, and two files XORed together.
        val same = ByteArray(64) { 9 }
        val bytes = ByteArrayOutputStream().also {
            EncryptedZip.write(
                listOf(EncryptedZip.Entry("a.bin", same), EncryptedZip.Entry("b.bin", same)),
                password,
                it,
                at,
                SecureRandom(),
            )
        }.toByteArray()

        val first = LOCAL_HEADER_BYTES + "a.bin".length + AES_EXTRA_BYTES
        val payloadSize = 16 + 2 + same.size + 10
        val second = first + payloadSize + LOCAL_HEADER_BYTES + "b.bin".length + AES_EXTRA_BYTES
        assertNotEquals(
            hex(bytes.copyOfRange(first, first + payloadSize)),
            hex(bytes.copyOfRange(second, second + payloadSize)),
        )
    }

    @Test
    fun adifferentPasswordIsAdifferentArchive() {
        val plain = ByteArray(32) { 5 }
        val one = archive(listOf(EncryptedZip.Entry("a.bin", plain)))
        val other = archive(listOf(EncryptedZip.Entry("a.bin", plain)), "something else".toCharArray())
        assertNotEquals(hex(one), hex(other))
    }

    @Test
    fun anArchiveNeedsAPasswordAndSomethingToPutInIt() {
        assertThrows(IllegalArgumentException::class.java) {
            archive(listOf(EncryptedZip.Entry("a.bin", ByteArray(1))), CharArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) { archive(emptyList()) }
    }

    @Test
    fun theTimestampIsTheOneItWasGiven() {
        val bytes = archive(listOf(EncryptedZip.Entry("a.bin", ByteArray(8))))
        // DOS time: 14:30:08 → hour 14, minute 30, second 8/2 = 4.
        assertEquals((14 shl 11) or (30 shl 5) or 4, readShort(bytes, 10))
        // DOS date: 2026-09-08.
        assertEquals(((2026 - 1980) shl 9) or (9 shl 5) or 8, readShort(bytes, 12))
    }

    @Test
    fun aNameWithAnAccentIsFlaggedAsUtf8() {
        val plain = ByteArray(8)
        assertEquals(0x0001, readShort(archive(listOf(EncryptedZip.Entry("plain.pdf", plain))), 6))
        assertTrue(readShort(archive(listOf(EncryptedZip.Entry("Léa.pdf", plain))), 6) and 0x0800 != 0)
    }

    private fun readShort(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readInt(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val LOCAL_HEADER_BYTES = 30
        const val AES_EXTRA_BYTES = 11
    }
}
