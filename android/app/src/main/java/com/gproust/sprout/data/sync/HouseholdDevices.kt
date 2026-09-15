package com.gproust.sprout.data.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A phone this one has heard from, as it last introduced itself. */
data class HouseholdDevice(
    val deviceId: String,
    /** The sender's own name, or empty for a replica written before ADR-0009. */
    val name: String,
    val lastSeen: Long,
)

/**
 * The other phones in the household, remembered as their replicas arrive
 * (ADR-0009).
 *
 * This is a **courtesy list, not access control**. Membership is possession of
 * the shared secret; a device that never sends a replica never appears here,
 * and forgetting one here does not shut it out. Removing someone for real means
 * rotating the secret — [PairingStore.rotateSecret].
 *
 * Kept per device, in preferences rather than the database, so it cannot travel
 * inside a replica: who *this* phone has heard from is not a fact about the
 * baby.
 */
class HouseholdDevices(private val context: Context) {

    fun all(): List<HouseholdDevice> {
        val raw = prefs().getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                HouseholdDevice(
                    deviceId = o.getString("deviceId"),
                    name = o.optString("name"),
                    lastSeen = o.getLong("lastSeen"),
                )
            }.getOrNull()
        }.sortedByDescending { it.lastSeen }
    }

    /**
     * Records that a replica arrived from [deviceId], keeping the newest name it
     * gave. A device that renames itself is the same device; a blank name never
     * overwrites one we already know.
     */
    fun seen(deviceId: String, name: String, at: Long) {
        if (deviceId.isBlank()) return
        val existing = all().associateBy { it.deviceId }.toMutableMap()
        val previous = existing[deviceId]
        existing[deviceId] = HouseholdDevice(
            deviceId = deviceId,
            name = name.ifBlank { previous?.name.orEmpty() },
            lastSeen = at,
        )
        write(existing.values.toList())
    }

    /** Drops a device from the list. Rotating the secret is what actually removes it. */
    fun forget(deviceId: String) = write(all().filterNot { it.deviceId == deviceId })

    fun clear() = prefs().edit { remove(KEY) }

    private fun write(devices: List<HouseholdDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("deviceId", device.deviceId)
                    put("name", device.name)
                    put("lastSeen", device.lastSeen)
                },
            )
        }
        prefs().edit { putString(KEY, array.toString()) }
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "settings"
        const val KEY = "sync_household_devices"
    }
}
