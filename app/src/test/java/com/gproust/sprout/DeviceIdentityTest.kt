package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.sync.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device id is what a pairing will attach to (ADR-0007), so the one thing it
 * must never do is change between two launches of the same install — and, since
 * ADR-0011, the one place it must never turn up is a backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdentityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun device() = context.getSharedPreferences(DeviceIdentity.PREFS, Context.MODE_PRIVATE)
    private fun settings() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        device().edit().clear().commit()
        settings().edit().clear().commit()
    }

    @Test
    fun staysTheSameOnceGenerated() {
        val first = DeviceIdentity.id(context)

        assertTrue(first.isNotEmpty())
        assertEquals(first, DeviceIdentity.id(context))
        assertEquals(first, DeviceIdentity.id(context))
    }

    @Test
    fun `lives apart from the settings, which are the ones that get backed up`() {
        val id = DeviceIdentity.id(context)

        assertEquals(id, device().getString("device_id", null))
        assertNull("nothing of the id belongs in the backed-up file", settings().all["device_id"])
    }

    @Test
    fun `an install from before the move keeps the id the household knows it by`() {
        settings().edit().putString("device_id", "id-from-1.8.0").commit()

        assertEquals("id-from-1.8.0", DeviceIdentity.id(context))
        // And from here on it is only in the file backups leave alone, so the
        // next new phone cannot inherit it.
        assertEquals("id-from-1.8.0", device().getString("device_id", null))
        assertNull(settings().all["device_id"])
    }

    @Test
    fun `a phone with no id of its own makes one rather than borrowing`() {
        val first = DeviceIdentity.id(context)

        // What a restored phone sees: the record arrived, the device file did not.
        device().edit().clear().commit()

        assertNotEquals(first, DeviceIdentity.id(context))
    }
}
