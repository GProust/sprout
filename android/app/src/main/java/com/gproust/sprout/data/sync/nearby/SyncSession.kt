package com.gproust.sprout.data.sync.nearby

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Raised when the other end says something this app cannot make sense of. */
class SyncSessionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * What two phones say to each other once they are connected (ADR-0010).
 *
 * Deliberately tiny, and deliberately ignorant of Bluetooth: it moves bytes over
 * a pair of streams, which is why the whole exchange can be tested over a pipe
 * without a radio in sight. What travels is exactly what phase 1 writes to a
 * file — a replica already sealed with AES-256-GCM under the household secret —
 * so this layer has no security of its own to get wrong.
 *
 * The exchange is symmetric: both ends run [exchange], both send, both receive,
 * and each merges what it got. One meeting brings both phones up to date, which
 * the file flow cannot do in a single step.
 */
object SyncSession {

    private const val MAGIC = "SPRTS"
    private const val VERSION: Byte = 1

    /**
     * A replica of a year's tracking is a few hundred kilobytes. A megabyte
     * ceiling leaves room for a family that logs far more than average, and
     * still refuses to allocate a buffer because the other end claimed a
     * ridiculous size.
     */
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

    /**
     * Sends [mine] and returns what the other phone sent, over an already-open
     * connection.
     *
     * Sending happens on its own thread while this one reads. That is not
     * decoration: both ends send before either reads, so on a link whose buffer
     * is smaller than a replica — which RFCOMM's is — a straightforward
     * write-then-read would have both phones blocked in `write`, waiting for a
     * reader that never arrives.
     *
     * @throws SyncSessionException if the other end is not a Sprout, speaks a
     * newer version, or announces more than [MAX_PAYLOAD_BYTES].
     */
    fun exchange(input: InputStream, output: OutputStream, mine: ByteArray): ByteArray {
        var sendFailure: Throwable? = null
        val sender = Thread {
            try {
                writeFrame(output, mine)
            } catch (e: Throwable) {
                sendFailure = e
            }
        }
        sender.start()
        try {
            val theirs = readFrame(input)
            sender.join()
            sendFailure?.let { throw SyncSessionException("could not send this phone's entries", it) }
            return theirs
        } catch (e: Throwable) {
            // Don't leave the sender running against a connection nobody reads.
            sender.join(JOIN_TIMEOUT_MS)
            throw e
        }
    }

    private fun writeFrame(output: OutputStream, payload: ByteArray) {
        DataOutputStream(output).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            writeByte(VERSION.toInt())
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    private fun readFrame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.length)
        try {
            data.readFully(magic)
        } catch (e: EOFException) {
            throw SyncSessionException("the other phone hung up before saying anything", e)
        }
        if (String(magic, Charsets.US_ASCII) != MAGIC) {
            throw SyncSessionException("the other end is not Sprout")
        }
        val version = data.readByte()
        if (version != VERSION) {
            throw SyncSessionException("the other phone speaks version $version; update Sprout")
        }
        val size = data.readInt()
        if (size < 0 || size > MAX_PAYLOAD_BYTES) {
            throw SyncSessionException("the other phone announced $size bytes, which is not plausible")
        }
        val payload = ByteArray(size)
        try {
            data.readFully(payload)
        } catch (e: EOFException) {
            throw SyncSessionException("the connection dropped part-way through", e)
        }
        return payload
    }

    private const val JOIN_TIMEOUT_MS = 1_000L
}
