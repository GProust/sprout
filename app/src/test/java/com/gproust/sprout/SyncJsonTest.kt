package com.gproust.sprout

import com.gproust.sprout.sync.DeviceSnapshot
import com.gproust.sprout.sync.FolderSyncBackend
import com.gproust.sprout.sync.Reconciler
import com.gproust.sprout.sync.SnapshotJson
import com.gproust.sprout.sync.SyncBackend
import com.gproust.sprout.sync.SyncRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is provided by the Android runtime, which Robolectric supplies to
// plain JVM unit tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncJsonTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = DeviceSnapshot(
        deviceId = "device-A",
        generatedAt = 1_700_000_000_000L,
        records = listOf(
            SyncRecord(
                syncId = "u1",
                table = "feeding",
                updatedAt = 100L,
                deviceId = "device-A",
                deleted = false,
                fields = mapOf("type" to "BREAST", "amountMl" to null, "notes" to "left side"),
            ),
            SyncRecord(
                syncId = "u2",
                table = "diaper",
                updatedAt = 200L,
                deviceId = "device-A",
                deleted = true,
            ),
        ),
    )

    @Test
    fun json_roundTripsLosslessly() {
        val decoded = SnapshotJson.decode(SnapshotJson.encode(sample))
        assertEquals(sample, decoded)
        // A null field value must survive as null, not the string "null" or missing.
        val u1 = decoded.records.first { it.syncId == "u1" }
        assertEquals(null, u1.fields["amountMl"])
        assertEquals(true, u1.fields.containsKey("amountMl"))
    }

    @Test
    fun fileNaming_isReversible() {
        val name = SyncBackend.fileNameFor("abc123")
        assertEquals("device-abc123.json", name)
        assertEquals("abc123", SyncBackend.deviceIdOf(name))
        assertNull(SyncBackend.deviceIdOf("notes.txt"))
    }

    @Test
    fun folderBackend_writesOwnFile_readsAll_andMerges() = runBlocking {
        val backend = FolderSyncBackend(tmp.newFolder("shared"))

        // Device A and device B each write their own file.
        val a = DeviceSnapshot(
            "A", 1L,
            listOf(SyncRecord("u1", "feeding", 10, "A", fields = mapOf("v" to "a"))),
        )
        val b = DeviceSnapshot(
            "B", 1L,
            listOf(
                SyncRecord("u1", "feeding", 20, "B", fields = mapOf("v" to "b")),
                SyncRecord("u2", "sleep", 5, "B", fields = mapOf("v" to "b2")),
            ),
        )
        backend.write(SyncBackend.fileNameFor("A"), SnapshotJson.encode(a))
        backend.write(SyncBackend.fileNameFor("B"), SnapshotJson.encode(b))

        // A reader pulls every device file and reconciles them.
        val snapshots = backend.list().mapNotNull { backend.read(it) }.map { SnapshotJson.decode(it) }
        val merged = Reconciler.mergeLive(snapshots).associateBy { it.syncId }

        assertEquals(2, merged.size)
        assertEquals("b", merged["u1"]!!.fields["v"]) // newer updatedAt from B wins
        assertEquals("b2", merged["u2"]!!.fields["v"])
    }
}
