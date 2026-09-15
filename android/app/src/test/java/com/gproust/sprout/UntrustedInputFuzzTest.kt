package com.gproust.sprout

import com.gproust.sprout.data.sync.SyncCrypto
import com.gproust.sprout.data.sync.SyncCryptoException
import com.gproust.sprout.data.sync.SyncFiles
import com.gproust.sprout.data.sync.SyncInvitation
import com.gproust.sprout.data.sync.SyncInvitationCodec
import com.gproust.sprout.data.sync.SyncInvitationException
import com.gproust.sprout.data.sync.SyncLimits
import com.gproust.sprout.data.sync.SyncPayload
import com.gproust.sprout.data.sync.SyncPayloadCodec
import com.gproust.sprout.data.sync.SyncPayloadException
import com.gproust.sprout.data.sync.SyncSecret
import com.gproust.sprout.data.sync.nearby.SyncSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

/**
 * Bytes Sprout did not write, handed to the code that reads them (ADR-0014).
 *
 * This is the app's only untrusted input, and it is genuinely reachable: the
 * manifest offers Sprout for `application/octet-stream`, because an invitation
 * coming back out of WhatsApp carries no file name to match on, so *any* app on
 * the phone can hand over a file that lands in one of these parsers. An
 * invitation is read before anything is authenticated — there is no shared key
 * yet, that being the point of an invitation — and a replica's header, length
 * and framing are all handled before the AES-GCM tag has said a word.
 *
 * The existing tests cover chosen bad inputs: a file that is not a replica, a
 * truncated frame. Chosen is the limit — they are the cases someone thought of.
 * What is asserted here is the property instead, over a few thousand mutations
 * of a valid file: **whatever the bytes, a parser either reads them or throws
 * the exception it documents.** Anything else — an index out of bounds, a null
 * dereference, an `OutOfMemoryError`, a `StackOverflowError` — is a file that
 * closes the app, sent by an app that only needed permission to share.
 *
 * The seed is fixed, so a failure here is a failure anyone can reproduce rather
 * than a Tuesday-only flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UntrustedInputFuzzTest {

    private companion object {
        const val SEED = 20260908L
        const val ITERATIONS = 2_000
        const val SESSION_ITERATIONS = 300
        const val NOW = 1_757_000_000_000L
        const val SCHEMA = 16
    }

    private val secret = SyncSecret(ByteArray(SyncSecret.SIZE_BYTES) { (it * 7 + 1).toByte() })

    /** A valid invitation: JSON, unauthenticated by design. */
    private val invitation: ByteArray = SyncInvitationCodec.encode(
        SyncInvitation(
            householdId = "household-1",
            secret = secret,
            createdAt = NOW,
            fromName = "Alex",
        ),
    )

    /** A valid payload: the JSON that lives inside a sealed replica. */
    private val payload: ByteArray = SyncPayloadCodec.encode(
        SyncPayload(
            householdId = "household-1",
            deviceId = "device-1",
            deviceName = "Alex's phone",
            createdAt = NOW,
            schemaVersion = SCHEMA,
        ),
    )

    /** A valid replica: that payload, gzipped and sealed under [secret]. */
    private val replica: ByteArray = SyncCrypto.seal(payload, secret)

    /**
     * A valid wire frame, produced by the session itself rather than spelled out
     * again here — a hand-written copy of the format would keep passing after
     * the format changed, testing nothing. `exchange` writes on its own thread
     * and joins it before rethrowing, so by the time the empty input has been
     * refused the frame is complete.
     */
    private val frame: ByteArray = ByteArrayOutputStream().also { out ->
        runCatching {
            SyncSession.exchange(ByteArrayInputStream(ByteArray(0)), out, replica)
        }
    }.toByteArray()

    // --- the property -------------------------------------------------------

    @Test
    fun `an invitation from anywhere is read or refused, never anything else`() {
        fuzz(invitation, ITERATIONS, SyncInvitationException::class.java, "invitation") {
            SyncInvitationCodec.decode(it, now = NOW)
        }
    }

    @Test
    fun `a replica from anywhere is opened or refused, never anything else`() {
        fuzz(replica, ITERATIONS, SyncCryptoException::class.java, "replica") {
            SyncCrypto.open(it, secret)
        }
    }

    /**
     * The payload decoder sits behind the GCM tag, so reaching it takes the
     * household secret. That makes this the one parser whose attacker is a
     * phone already in the household — a lower bar to clear, and still a bar
     * worth having: a corrupted replica should be refused, not fatal.
     */
    @Test
    fun `a payload from anywhere is decoded or refused, never anything else`() {
        fuzz(payload, ITERATIONS, SyncPayloadException::class.java, "payload") {
            SyncPayloadCodec.decode(it, currentSchemaVersion = SCHEMA)
        }
    }

    @Test
    fun `a nearby phone can only be understood or hung up on`() {
        fuzz(frame, SESSION_ITERATIONS, IOException::class.java, "session") {
            SyncSession.exchange(ByteArrayInputStream(it), ByteArrayOutputStream(), replica)
        }
    }

    // --- the ceilings the property leans on ---------------------------------

    /**
     * The case the property test cannot reach by mutation and an attacker
     * reaches by typing: nesting deep enough to exhaust the stack. It has to be
     * refused *before* the parse, because `StackOverflowError` is an `Error` and
     * every decoder's `catch (e: Exception)` would let it past.
     */
    @Test
    fun `a deeply nested file is refused rather than parsed`() {
        val nested = ("{\"a\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}").toByteArray()

        assertThrows(SyncInvitationException::class.java) {
            SyncInvitationCodec.decode(nested, now = NOW)
        }
        assertThrows(SyncPayloadException::class.java) {
            SyncPayloadCodec.decode(nested, currentSchemaVersion = SCHEMA)
        }
    }

    @Test
    fun `the depth scan counts structure and not what a parent typed`() {
        // A note that is nothing but brackets is a note, not a document.
        val brackets = "[".repeat(SyncLimits.MAX_JSON_DEPTH + 10)
        assertFalse(SyncLimits.exceedsMaxJsonDepth("{\"notes\":\"$brackets\"}"))
        assertTrue(SyncLimits.exceedsMaxJsonDepth("{\"notes\":[$brackets]}"))

        // A backslash escapes the quote after it, so the string stays open.
        assertFalse(SyncLimits.exceedsMaxJsonDepth("{\"notes\":\"a\\\"$brackets\"}"))

        // A real file is nowhere near the ceiling.
        assertFalse(SyncLimits.exceedsMaxJsonDepth(String(invitation, Charsets.UTF_8)))
        assertFalse(SyncLimits.exceedsMaxJsonDepth(String(payload, Charsets.UTF_8)))
    }

    @Test
    fun `a file larger than the ceiling is refused instead of read into memory`() {
        val oversized = ByteArray(SyncLimits.MAX_FILE_BYTES + 1)

        assertThrows(IllegalArgumentException::class.java) {
            SyncFiles.readCapped(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun `a file at the ceiling still reads`() {
        val atLimit = ByteArray(SyncLimits.MAX_FILE_BYTES) { it.toByte() }

        assertEquals(SyncLimits.MAX_FILE_BYTES, SyncFiles.readCapped(ByteArrayInputStream(atLimit)).size)
    }

    @Test
    fun `an ordinary file reads back exactly`() {
        assertTrue(SyncFiles.readCapped(ByteArrayInputStream(invitation)).contentEquals(invitation))
    }

    // --- the fuzzer ---------------------------------------------------------

    /**
     * Runs [parse] over mutations of [seed] plus a fixed set of awkward inputs,
     * and fails on any throwable that is not [allowed].
     *
     * A mutation that happens to produce a *valid* file is fine: the property is
     * "read it or refuse it in the documented way", not "always refuse".
     */
    private fun fuzz(
        seed: ByteArray,
        iterations: Int,
        allowed: Class<out Throwable>,
        label: String,
        parse: (ByteArray) -> Unit,
    ) {
        val random = Random(SEED)
        for (i in 0 until iterations) {
            check(mutate(seed, random), allowed, "$label #$i", parse)
        }
        for ((i, awkward) in awkwardInputs().withIndex()) {
            check(awkward, allowed, "$label awkward #$i", parse)
        }
    }

    private fun check(
        input: ByteArray,
        allowed: Class<out Throwable>,
        label: String,
        parse: (ByteArray) -> Unit,
    ) {
        try {
            parse(input)
        } catch (t: Throwable) {
            if (!allowed.isInstance(t)) {
                throw AssertionError(
                    "$label: ${input.size} bytes produced ${t.javaClass.name} " +
                        "(\"${t.message}\") instead of ${allowed.simpleName}. " +
                        "First bytes: ${input.take(48).joinToString(" ") { b -> "%02x".format(b) }}",
                    t,
                )
            }
        }
    }

    private fun mutate(seed: ByteArray, random: Random): ByteArray = when (random.nextInt(7)) {
        // Flip one bit — the mutation most likely to leave the file almost valid.
        0 -> seed.copyOf().also {
            val at = random.nextInt(it.size)
            it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        // Replace one byte outright.
        1 -> seed.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
        // Cut it short, anywhere.
        2 -> seed.copyOf(random.nextInt(seed.size + 1))
        // Add bytes nobody asked for.
        3 -> seed + ByteArray(1 + random.nextInt(64)) { random.nextInt(256).toByte() }
        // Repeat a stretch of it, which is what a bad copy looks like.
        4 -> {
            val from = random.nextInt(seed.size)
            val to = from + random.nextInt(seed.size - from) + 1
            seed.copyOfRange(0, to) + seed.copyOfRange(from, to) + seed.copyOfRange(to, seed.size)
        }
        // Blank a stretch of it.
        5 -> seed.copyOf().also {
            val from = random.nextInt(it.size)
            val to = from + random.nextInt(it.size - from) + 1
            it.fill(0, from, to)
        }
        // Keep only the length.
        else -> ByteArray(seed.size) { random.nextInt(256).toByte() }
    }

    /** Inputs worth trying against every parser, mutation or no mutation. */
    private fun awkwardInputs(): List<ByteArray> = listOf(
        ByteArray(0),
        ByteArray(1),
        ByteArray(4096),
        "{}".toByteArray(),
        "[]".toByteArray(),
        "null".toByteArray(),
        "{\"formatVersion\":".toByteArray(),
        // Right shape, wrong types.
        """{"formatVersion":1,"householdId":[],"secret":{},"createdAt":"soon"}""".toByteArray(),
        // A number no Long will hold.
        """{"formatVersion":1,"householdId":"h","secret":"AAAA","createdAt":999999999999999999999}"""
            .toByteArray(),
        // Versions at the edges.
        """{"formatVersion":-1,"schemaVersion":-1}""".toByteArray(),
        """{"formatVersion":2147483647,"schemaVersion":2147483647}""".toByteArray(),
        // A long value, and an unterminated string.
        ("{\"fromName\":\"" + "x".repeat(100_000) + "\"}").toByteArray(),
        "{\"fromName\":\"unterminated".toByteArray(),
        // Nesting, closed and open.
        ("[".repeat(50_000) + "]".repeat(50_000)).toByteArray(),
        "[".repeat(50_000).toByteArray(),
        // Not UTF-8 at all.
        ByteArray(256) { (it - 128).toByte() },
    )
}
