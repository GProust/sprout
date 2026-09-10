package com.gproust.sprout

import com.gproust.sprout.data.sync.nearby.SyncSession
import com.gproust.sprout.data.sync.nearby.SyncSessionException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The conversation two phones have once they are connected (ADR-0010).
 *
 * It runs over a pair of pipes here, which is the point of keeping it ignorant
 * of Bluetooth: the whole exchange — including the deadlock it is shaped to
 * avoid — can be proven in CI, with no radio anywhere.
 */
class SyncSessionTest {

    /** Two phones, wired to each other. */
    private class Link {
        private val aToB = PipedOutputStream()
        private val bToA = PipedOutputStream()
        val aIn = PipedInputStream(bToA, BUFFER)
        val bIn = PipedInputStream(aToB, BUFFER)
        val aOut: PipedOutputStream get() = aToB
        val bOut: PipedOutputStream get() = bToA

        private companion object {
            /**
             * Deliberately smaller than the payloads below — an RFCOMM link
             * buffers a few kilobytes, and a session that only works when the
             * buffer is bigger than the data would deadlock on a real phone
             * while passing a naive test.
             */
            const val BUFFER = 4 * 1024
        }
    }

    private fun replica(size: Int, seed: Int) = ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test
    fun `both phones come away with the other's entries`() {
        val link = Link()
        val alex = replica(64 * 1024, seed = 1)
        val sam = replica(48 * 1024, seed = 2)
        val pool = Executors.newFixedThreadPool(2)

        val alexGot = pool.submit(Callable { SyncSession.exchange(link.aIn, link.aOut, alex) })
        val samGot = pool.submit(Callable { SyncSession.exchange(link.bIn, link.bOut, sam) })

        // One meeting brings both up to date — the file flow needs two passes.
        assertArrayEquals(sam, alexGot.get(10, TimeUnit.SECONDS))
        assertArrayEquals(alex, samGot.get(10, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test
    fun `a payload larger than the link buffer does not deadlock both ends`() {
        val link = Link()
        // Sixteen times the pipe: if either side wrote everything before
        // reading, both would block in write with nobody left to drain them.
        val big = replica(64 * 1024, seed = 3)
        val pool = Executors.newFixedThreadPool(2)

        val one = pool.submit(Callable { SyncSession.exchange(link.aIn, link.aOut, big) })
        val two = pool.submit(Callable { SyncSession.exchange(link.bIn, link.bOut, big) })

        assertArrayEquals(big, one.get(10, TimeUnit.SECONDS))
        assertArrayEquals(big, two.get(10, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test
    fun `an empty replica is a legitimate thing to send`() {
        val link = Link()
        val pool = Executors.newFixedThreadPool(2)

        val one = pool.submit(Callable { SyncSession.exchange(link.aIn, link.aOut, ByteArray(0)) })
        val two = pool.submit(Callable { SyncSession.exchange(link.bIn, link.bOut, replica(100, 4)) })

        assertEquals(0, two.get(10, TimeUnit.SECONDS).size)
        assertEquals(100, one.get(10, TimeUnit.SECONDS).size)
        pool.shutdown()
    }

    @Test
    fun `something that is not Sprout on the other end is refused`() {
        val failure = assertThrows(SyncSessionException::class.java) {
            SyncSession.exchange(
                ByteArrayInputStream("hello from some other app".toByteArray()),
                ByteArrayOutputStream(),
                ByteArray(0),
            )
        }
        assertTrue(failure.message!!.contains("not Sprout"))
    }

    @Test
    fun `a newer Sprout on the other end is refused rather than misread`() {
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                write("SPRTS".toByteArray())
                writeByte(99)
                writeInt(0)
            }
        }

        val failure = assertThrows(SyncSessionException::class.java) {
            SyncSession.exchange(ByteArrayInputStream(header.toByteArray()), ByteArrayOutputStream(), ByteArray(0))
        }
        assertTrue(failure.message!!.contains("update Sprout"))
    }

    @Test
    fun `an implausible size is refused before anything is allocated`() {
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                write("SPRTS".toByteArray())
                writeByte(1)
                writeInt(Int.MAX_VALUE)
            }
        }

        assertThrows(SyncSessionException::class.java) {
            SyncSession.exchange(ByteArrayInputStream(header.toByteArray()), ByteArrayOutputStream(), ByteArray(0))
        }
    }

    @Test
    fun `a connection that drops part-way through is an error, not half a replica`() {
        val truncated = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                write("SPRTS".toByteArray())
                writeByte(1)
                writeInt(1_000)
                write(ByteArray(10))
            }
        }

        val failure = assertThrows(SyncSessionException::class.java) {
            SyncSession.exchange(ByteArrayInputStream(truncated.toByteArray()), ByteArrayOutputStream(), ByteArray(0))
        }
        assertTrue(failure.message!!.contains("dropped"))
    }
}
