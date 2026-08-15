package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.sync.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device id is what a pairing will attach to (ADR-0007), so the one thing it
 * must never do is change between two launches of the same install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdentityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun staysTheSameOnceGenerated() {
        val first = DeviceIdentity.id(context)

        assertTrue(first.isNotEmpty())
        assertEquals(first, DeviceIdentity.id(context))
        assertEquals(first, DeviceIdentity.id(context))
    }
}
