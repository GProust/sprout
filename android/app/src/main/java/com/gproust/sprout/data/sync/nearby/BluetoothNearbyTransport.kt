package com.gproust.sprout.data.sync.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The radio (ADR-0010): BLE to say "a phone of this household is here", plain
 * RFCOMM to hand over the replica.
 *
 * Deliberately thin. There is no way to test any of this without two phones —
 * CI has none — so everything with a decision in it lives in [NearbySync],
 * [NearbyPolicy] and [HouseholdBeacon], which are tested, and this file is kept
 * to the mechanical part.
 *
 * The RFCOMM socket is the *insecure* variety, which means the Bluetooth link
 * has no authentication or encryption of its own. That is correct here, and
 * only here: what crosses it is a replica already sealed with AES-256-GCM under
 * the household secret. Link-layer security would protect protected data, and
 * would cost a second pairing dialog on top of the one Sprout already does. It
 * also means the phones do **not** need to be paired in Android's Bluetooth
 * settings — pairing them there changes nothing either way.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BluetoothNearbyTransport(private val context: Context) : NearbyTransport {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun unavailableReason(): NearbyTransport.Unavailable? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> NearbyTransport.Unavailable.TOO_OLD
        adapter == null -> NearbyTransport.Unavailable.NO_BLUETOOTH
        !hasPermissions() -> NearbyTransport.Unavailable.NO_PERMISSION
        adapter?.isEnabled != true -> NearbyTransport.Unavailable.BLUETOOTH_OFF
        else -> null
    }

    /**
     * Opens the window: advertise, scan, and exchange with whoever we recognise
     * — all of it stopping when the window closes, whether or not anyone came.
     */
    @SuppressLint("MissingPermission") // guarded by unavailableReason(), checked before every window
    override suspend fun exchange(
        beacon: ByteArray,
        isOurs: (ByteArray) -> Boolean,
        mine: ByteArray,
        windowMs: Long,
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        val adapter = adapter ?: throw NearbyTransport.RadioRefused("no adapter")
        val received = Collections.synchronizedList(mutableListOf<ByteArray>())
        // Connecting is a blocking RFCOMM dial plus a whole exchange. Doing it
        // on the scan callback — a system thread — stalls delivery of the other
        // phones' results, so it happens here instead.
        val dialling = Executors.newCachedThreadPool()
        val failures = Collections.synchronizedList(mutableListOf<String>())

        val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // Silence here is how the first version of this hid a broken
                // window for a whole release: nothing advertised, nothing
                // found, and "nobody nearby" reported as though that were news.
                failures += "advertising refused ($errorCode)"
            }
        }
        runCatching { advertiser?.startAdvertising(settings(), advertisement(beacon), advertiseCallback) }

        // Accepting and connecting both run for the window: whichever phone
        // gets there first drives, and the other answers.
        val server = runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, HouseholdBeacon.SERVICE_UUID)
        }.getOrNull()

        val accepting = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val socket = runCatching { server?.accept() }.getOrNull() ?: return@Thread
                runCatching { socket.use { received += SyncSession.exchange(it.inputStream, it.outputStream, mine) } }
            }
        }.apply { isDaemon = true; start() }

        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        val met = Collections.synchronizedSet(mutableSetOf<String>())
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertised = result.scanRecord
                    ?.getServiceData(ParcelUuid(HouseholdBeacon.SERVICE_UUID)) ?: return
                // Whether this beacon is one of ours is the caller's rule, not
                // ours — it is the only side that holds the household secret,
                // and it accepts the previous half-hour as well as this one.
                if (!isOurs(advertised)) return
                if (!met.add(result.device.address)) return
                val device = result.device
                dialling.execute { connect(device, mine)?.let { received += it } }
            }

            override fun onScanFailed(errorCode: Int) {
                failures += "scanning refused ($errorCode)"
            }
        }
        runCatching { scanner?.startScan(listOf(filter()), scanSettings(), scanCallback) }

        try {
            delay(windowMs)
        } finally {
            runCatching { scanner?.stopScan(scanCallback) }
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            // Closing the server socket is what unblocks a thread parked in
            // accept(); interrupting one on its own would not.
            runCatching { server?.close() }
            accepting.interrupt()
            // A dial started just before the window closed is worth finishing:
            // the exchange itself takes well under a second once connected.
            dialling.shutdown()
            runCatching { dialling.awaitTermination(DIAL_GRACE_MS, TimeUnit.MILLISECONDS) }
            dialling.shutdownNow()
        }
        // A window in which neither half of the radio ever started is a
        // failure, not an empty room, and saying so is the difference between
        // "nobody answered" and "turn something back on".
        if (received.isEmpty() && failures.isNotEmpty()) {
            throw NearbyTransport.RadioRefused(failures.joinToString("; "))
        }
        received.toList()
    }

    @SuppressLint("MissingPermission") // same guard as exchange()
    private fun connect(device: BluetoothDevice, mine: ByteArray): ByteArray? {
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createInsecureRfcommSocketToServiceRecord(HouseholdBeacon.SERVICE_UUID)
            // Discovery and connecting at once is a known way to make both slow.
            runCatching { adapter?.cancelDiscovery() }
            socket.connect()
            SyncSession.exchange(socket.inputStream, socket.outputStream, mine)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun advertisement(beacon: ByteArray): AdvertiseData = AdvertiseData.Builder()
        // The device name would defeat the point of a rotating beacon.
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceData(ParcelUuid(HouseholdBeacon.SERVICE_UUID), beacon)
        .build()

    private fun settings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
        .setConnectable(true)
        .build()

    /**
     * Matches any advertisement carrying service data under Sprout's UUID.
     *
     * `setServiceData`, **not** `setServiceUuid`, and the distinction is the
     * whole reason nothing was ever found: a scan filter's service-uuid clause
     * is matched against the advertised *list of service UUIDs* (AD types
     * 0x02–0x07), while what Sprout advertises is *service data* (AD type
     * 0x21), which the scan record parses into a different field entirely. The
     * filter could never match, so the callback never fired, and two phones
     * side by side each reported an empty room.
     *
     * Advertising the UUID in both fields would be the other way out, and does
     * not fit: 3 bytes of flags plus 18 for a 128-bit service UUID plus 26 for
     * the same UUID again with 8 bytes of data is well past the 31 an
     * advertisement has. Filtering on the data is both correct and free.
     *
     * Empty data with an empty mask means "any value" — the beacon itself is
     * checked in code, where the previous half-hour is accepted too.
     */
    private fun filter(): ScanFilter = ScanFilter.Builder()
        .setServiceData(ParcelUuid(HouseholdBeacon.SERVICE_UUID), ByteArray(0), ByteArray(0))
        .build()

    private fun scanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    private fun hasPermissions(): Boolean = NearbyPermissions.granted(context)

    private companion object {
        const val SERVICE_NAME = "Sprout household sync"

        /** How long a dial already under way may finish after the window shuts. */
        const val DIAL_GRACE_MS = 5_000L
    }
}

/**
 * What automatic sync needs from the phone and from the parent.
 *
 * Outside the API-gated transport on purpose: a screen has to be able to ask
 * "can this phone do it at all?" without being gated itself.
 */
object NearbyPermissions {

    /**
     * What the parent is asked for, and only when they turn the feature on.
     * `ACCESS_FINE_LOCATION` is not among them and never will be — asking for a
     * location to sync a feeding log is the trade ADR-0010 refuses, and it is
     * why the feature is offered on API 31+ only.
     */
    val REQUIRED = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** Whether this phone is new enough to be offered automatic sync at all. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun granted(context: Context): Boolean = REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
