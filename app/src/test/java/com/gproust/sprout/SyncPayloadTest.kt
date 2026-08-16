package com.gproust.sprout

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.data.local.StoolColor
import com.gproust.sprout.data.local.TombstoneEntity
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.data.sync.BabyScoped
import com.gproust.sprout.data.sync.SyncCrypto
import com.gproust.sprout.data.sync.SyncCryptoException
import com.gproust.sprout.data.sync.SyncPayload
import com.gproust.sprout.data.sync.SyncPayloadCodec
import com.gproust.sprout.data.sync.SyncPayloadException
import com.gproust.sprout.data.sync.SyncSecret
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The replica two phones exchange: what it must survive, and what it must
 * refuse.
 *
 * A payload is written by one version of Sprout and read by another, possibly
 * months later, after crossing a messaging app. So the round trip has to be
 * lossless down to the null-versus-absent distinction, and everything else has
 * to fail loudly rather than half-apply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncPayloadTest {

    private val babyUid = "baby-uid-1"

    private fun fullPayload() = SyncPayload(
        householdId = "household-1",
        deviceId = "device-a",
        createdAt = 1_700_000_000_000,
        schemaVersion = 15,
        babies = listOf(
            BabyEntity(
                name = "Léa",
                birthDate = 1_699_000_000_000,
                archived = false,
                feedingReminderEnabled = true,
                feedingReminderIntervalMinutes = 180,
                uid = babyUid,
                updatedAt = 10,
            ),
            // Every optional field left empty, to catch null/absent confusion.
            BabyEntity(name = "Sam", birthDate = 1_699_000_000_001, uid = "baby-uid-2", updatedAt = 11),
        ),
        feedings = listOf(
            BabyScoped(
                babyUid,
                FeedingEntity(
                    type = FeedType.BREAST,
                    side = BreastSide.BOTH,
                    startTime = 1_700_000_100_000,
                    endTime = 1_700_000_900_000,
                    leftDurationMs = 300_000,
                    rightDurationMs = 500_000,
                    segments = listOf(
                        NursingSegment(BreastSide.LEFT, 1_700_000_100_000, 1_700_000_400_000),
                        NursingSegment(BreastSide.RIGHT, 1_700_000_400_000, 1_700_000_900_000),
                    ),
                    notes = "sleepy, with a \"quote\" and a comma, too",
                    uid = "feed-1",
                    updatedAt = 20,
                ),
            ),
            BabyScoped(
                babyUid,
                FeedingEntity(
                    type = FeedType.BOTTLE,
                    amountMl = 90,
                    startTime = 1_700_001_000_000,
                    uid = "feed-2",
                    updatedAt = 21,
                    deletedAt = 22,
                ),
            ),
            // A solid, weighed — the field a phone on schema 14 knows nothing about.
            BabyScoped(
                babyUid,
                FeedingEntity(
                    type = FeedType.SOLID,
                    amountGrams = 75,
                    startTime = 1_700_001_500_000,
                    uid = "feed-3",
                    updatedAt = 23,
                ),
            ),
        ),
        diapers = listOf(
            BabyScoped(
                babyUid,
                DiaperEntity(
                    time = 1_700_002_000_000,
                    wet = true,
                    dirty = true,
                    stoolColor = StoolColor.YELLOW,
                    uid = "diaper-1",
                    updatedAt = 30,
                ),
            ),
        ),
        treatments = listOf(
            BabyScoped(
                babyUid,
                TreatmentEntity(
                    name = "Vitamine D",
                    dose = "1 goutte",
                    intervalDays = 1,
                    timesOfDay = listOf(540, 1200),
                    startDate = 1_699_000_000_000,
                    endDate = null,
                    uid = "treat-1",
                    updatedAt = 40,
                ),
            ),
        ),
        pumpings = listOf(
            PumpingEntity(
                time = 1_700_003_000_000,
                amountMl = 120,
                side = BreastSide.LEFT,
                storage = MilkStorage.FREEZER,
                uid = "pump-1",
                updatedAt = 50,
            ),
        ),
        tombstones = listOf(TombstoneEntity(uid = "gone-1", entity = "sleep", deletedAt = 60)),
    )

    @Test
    fun `a payload survives the round trip unchanged`() {
        val original = fullPayload()

        val decoded = SyncPayloadCodec.decode(SyncPayloadCodec.encode(original), currentSchemaVersion = 15)

        assertEquals(original.householdId, decoded.householdId)
        assertEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.babies, decoded.babies)
        assertEquals(original.feedings, decoded.feedings)
        assertEquals(original.diapers, decoded.diapers)
        assertEquals(original.treatments, decoded.treatments)
        assertEquals(original.pumpings, decoded.pumpings)
        assertEquals(original.tombstones, decoded.tombstones)
    }

    @Test
    fun `an empty optional comes back null, not blank`() {
        val decoded = SyncPayloadCodec.decode(SyncPayloadCodec.encode(fullPayload()), 15)

        val sam = decoded.babies.single { it.uid == "baby-uid-2" }
        assertNull(sam.feedingReminderEnabled)
        assertNull(sam.feedingReminderIntervalMinutes)
        assertNull(sam.deletedAt)
        val bottle = decoded.feedings.single { it.row.uid == "feed-2" }.row
        assertNull("a bottle has no side", bottle.side)
        assertNull(bottle.notes)
        assertTrue(bottle.segments.isEmpty())
    }

    @Test
    fun `a deleted row travels flagged, which is how the deletion travels at all`() {
        val decoded = SyncPayloadCodec.decode(SyncPayloadCodec.encode(fullPayload()), 15)

        assertEquals(22L, decoded.feedings.single { it.row.uid == "feed-2" }.row.deletedAt)
    }

    @Test
    fun `local ids never leave the phone`() {
        val json = String(SyncPayloadCodec.encode(fullPayload()))

        // The primary key is a per-device counter; sending it would be sending a
        // number that means something else on the other phone.
        assertTrue("payload must not carry `id`", !json.contains("\"id\""))
        assertTrue("a log points at its baby by uid", json.contains("\"babyUid\""))
        assertTrue("payload must not carry `babyId`", !json.contains("\"babyId\""))
    }

    @Test
    fun `a replica from a newer Sprout is refused rather than half-applied`() {
        val bytes = SyncPayloadCodec.encode(fullPayload().copy(schemaVersion = 99))

        val failure = assertThrows(SyncPayloadException.TooNew::class.java) {
            SyncPayloadCodec.decode(bytes, currentSchemaVersion = 15)
        }
        assertEquals(99, failure.schemaVersion)
    }

    @Test
    fun `something that is not a replica is refused`() {
        assertThrows(SyncPayloadException.Unreadable::class.java) {
            SyncPayloadCodec.decode("not a replica at all".toByteArray(), 14)
        }
        assertThrows(SyncPayloadException.Unreadable::class.java) {
            SyncPayloadCodec.decode("""{"hello":"world"}""".toByteArray(), 14)
        }
    }

    // --- what leaves the phone ---------------------------------------------

    @Test
    fun `what leaves the phone is opaque, and comes back whole`() {
        val secret = SyncSecret.random()
        val plaintext = SyncPayloadCodec.encode(fullPayload())

        val sealed = SyncCrypto.seal(plaintext, secret)

        assertTrue("the baby's name must not be readable in the file", !String(sealed).contains("Léa"))
        assertArrayEquals(plaintext, SyncCrypto.open(sealed, secret))
    }

    @Test
    fun `another household's secret cannot open it`() {
        val sealed = SyncCrypto.seal("the private bits".toByteArray(), SyncSecret.random())

        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(sealed, SyncSecret.random()) }
    }

    @Test
    fun `a replica altered on the way is refused, not repaired`() {
        val secret = SyncSecret.random()
        val sealed = SyncCrypto.seal("the private bits".toByteArray(), secret)

        // Flip a bit deep in the ciphertext, and another in the framing header.
        val body = sealed.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        val head = sealed.copyOf().also { it[4] = 9 }

        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(body, secret) }
        assertThrows(SyncCryptoException::class.java) { SyncCrypto.open(head, secret) }
    }

    @Test
    fun `both phones can read the same verification code, and only they can`() {
        val secret = SyncSecret(ByteArray(32) { it.toByte() })

        val code = secret.verificationCode()

        assertEquals("the same secret always reads the same", code, SyncSecret(ByteArray(32) { it.toByte() }).verificationCode())
        assertEquals(6, code.length)
        assertTrue("no characters that get misheard aloud", code.none { it in "01OI" })
        assertNotEquals(code, SyncSecret.random().verificationCode())
    }
}
