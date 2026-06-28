package com.gproust.sprout

import com.gproust.sprout.sync.DeviceSnapshot
import com.gproust.sprout.sync.Reconciler
import com.gproust.sprout.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcilerTest {

    private fun rec(
        id: String,
        updatedAt: Long,
        device: String,
        deleted: Boolean = false,
        value: String? = null,
    ) = SyncRecord(
        syncId = id,
        table = "feeding",
        updatedAt = updatedAt,
        deviceId = device,
        deleted = deleted,
        fields = if (value != null) mapOf("v" to value) else emptyMap(),
    )

    private fun snapshot(device: String, vararg records: SyncRecord) =
        DeviceSnapshot(deviceId = device, generatedAt = 0L, records = records.toList())

    @Test
    fun disjointRecords_areUnioned() {
        val a = snapshot("A", rec("1", 10, "A"))
        val b = snapshot("B", rec("2", 10, "B"))
        val merged = Reconciler.merge(listOf(a, b))
        assertEquals(listOf("1", "2"), merged.map { it.syncId })
    }

    @Test
    fun sameRecord_newerUpdatedAtWins() {
        val a = snapshot("A", rec("1", 10, "A", value = "old"))
        val b = snapshot("B", rec("1", 20, "B", value = "new"))
        val merged = Reconciler.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("new", merged.single().fields["v"])
    }

    @Test
    fun tieOnUpdatedAt_brokenByDeviceId_deterministically() {
        val a = snapshot("A", rec("1", 10, "A", value = "fromA"))
        val b = snapshot("B", rec("1", 10, "B", value = "fromB"))
        // "B" > "A", so B wins regardless of input order.
        assertEquals("fromB", Reconciler.merge(listOf(a, b)).single().fields["v"])
        assertEquals("fromB", Reconciler.merge(listOf(b, a)).single().fields["v"])
    }

    @Test
    fun newerDeleteWinsOverOlderEdit() {
        val edit = snapshot("A", rec("1", 10, "A", value = "alive"))
        val delete = snapshot("B", rec("1", 20, "B", deleted = true))
        val merged = Reconciler.merge(listOf(edit, delete))
        assertTrue(merged.single().deleted)
        assertTrue(Reconciler.mergeLive(listOf(edit, delete)).isEmpty())
    }

    @Test
    fun editAfterDelete_resurrectsRow() {
        val delete = snapshot("A", rec("1", 10, "A", deleted = true))
        val edit = snapshot("B", rec("1", 20, "B", value = "back"))
        val live = Reconciler.mergeLive(listOf(delete, edit))
        assertEquals("back", live.single().fields["v"])
    }

    @Test
    fun merge_isOrderIndependent() {
        val a = snapshot("A", rec("1", 30, "A", value = "a1"), rec("2", 10, "A", value = "a2"))
        val b = snapshot("B", rec("1", 20, "B", value = "b1"), rec("3", 5, "B", value = "b3"))
        val c = snapshot("C", rec("2", 40, "C", value = "c2"), rec("1", 30, "C", value = "c1"))

        val one = Reconciler.merge(listOf(a, b, c))
        val two = Reconciler.merge(listOf(c, a, b))
        val three = Reconciler.merge(listOf(b, c, a))
        assertEquals(one, two)
        assertEquals(two, three)
        // record 1: A and C tie at 30 -> deviceId "C" wins; record 2: C at 40 wins.
        val byId = one.associateBy { it.syncId }
        assertEquals("c1", byId["1"]!!.fields["v"])
        assertEquals("c2", byId["2"]!!.fields["v"])
        assertEquals("b3", byId["3"]!!.fields["v"])
    }

    @Test
    fun merge_isIdempotent() {
        val a = snapshot("A", rec("1", 10, "A", value = "x"))
        val b = snapshot("B", rec("1", 20, "B", value = "y"), rec("2", 5, "B", value = "z"))
        val once = Reconciler.merge(listOf(a, b))
        // Re-merging the already-merged result with itself changes nothing.
        val twice = Reconciler.merge(listOf(DeviceSnapshot("M", 0L, once)))
        assertEquals(once, twice)
    }
}
