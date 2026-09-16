package com.gproust.sprout.data.sync.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The radio (ADR-0010, transport and advertisement amended by ADR-0016): BLE to
 * say "a phone of this household is here", then a stream to hand the replica
 * over.
 *
 * Deliberately thin. There is no way to test any of this without two phones —
 * CI has none — so everything with a decision in it lives in [NearbySync],
 * [NearbyPolicy], [HouseholdBeacon] and [L2capPsm], which are tested, and this
 * file is kept to the mechanical part.
 *
 * ### Two of everything, for one release
 *
 * ADR-0016 moves the exchange from RFCOMM to an L2CAP channel and the
 * advertisement from service data into a derived service UUID, because an
 * iPhone can do neither of the originals. Every Sprout already installed speaks
 * only the originals, so for one release this phone does **both**: it advertises
 * both forms, scans for both, and listens on both. The old pair goes when the
 * release after this one lands; dropping it now would leave every existing
 * paired household unable to sync until both phones updated, which is a failure
 * nothing reports.
 *
 * Both sockets are the *insecure* variety, which means the Bluetooth link has no
 * authentication or encryption of its own. That is correct here, and only here:
 * what crosses it is a replica already sealed with AES-256-GCM under the
 * household secret. Link-layer security would protect protected data, and would
 * cost a second pairing dialog on top of the one Sprout already does. It also
 * means the phones do **not** need to be paired in Android's Bluetooth settings.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BluetoothNearbyTransport(private val context: Context) : NearbyTransport {

    private val manager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = manager?.adapter

    override fun unavailableReason(): NearbyTransport.Unavailable? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> NearbyTransport.Unavailable.TOO_OLD
        adapter == null -> NearbyTransport.Unavailable.NO_BLUETOOTH
        !hasPermissions() -> NearbyTransport.Unavailable.NO_PERMISSION
        adapter?.isEnabled != true -> NearbyTransport.Unavailable.BLUETOOTH_OFF
        else -> null
    }

    /**
     * Opens the window: advertise, listen, scan, and exchange with whoever we
     * recognise — all of it stopping when the window closes, whether or not
     * anyone came.
     */
    @SuppressLint("MissingPermission") // guarded by unavailableReason(), checked before every window
    override suspend fun exchange(
        beacon: ByteArray,
        advertUuid: UUID,
        scanUuids: List<UUID>,
        isOurs: (ByteArray) -> Boolean,
        mine: ByteArray,
        windowMs: Long,
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        val adapter = adapter ?: throw NearbyTransport.RadioRefused("no adapter")
        val received: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        // Connecting is a blocking dial plus a whole exchange. Doing it on the
        // scan callback — a system thread — stalls delivery of the other phones'
        // results, so it happens here instead.
        val dialling = Executors.newCachedThreadPool()
        val scanFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val advertiseFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val advertisingForms = AtomicInteger(0)

        // --- what goes out ---------------------------------------------------
        //
        // Two advertisements rather than one packet carrying both forms, and the
        // arithmetic is why: 3 bytes of flags, plus 2 + 16 for a 128-bit service
        // UUID, plus 2 + 16 + 8 for the same-sized service data, is 47 against
        // the 31 an advertisement has. Splitting them also keeps each form in a
        // primary advertising packet, which is what both an un-updated Android's
        // service-data filter and an iPhone's `scanForPeripherals(withServices:)`
        // match against — a scan response would be a gamble on two stacks at
        // once.
        val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        val serviceDataForm = advertiseCallback("advertising", advertisingForms, advertiseFailures)
        val derivedUuidForm = advertiseCallback("advertising the derived uuid", advertisingForms, advertiseFailures)
        runCatching {
            advertiser?.startAdvertising(settings(), serviceDataAdvertisement(beacon), serviceDataForm)
        }
        runCatching {
            advertiser?.startAdvertising(settings(), derivedUuidAdvertisement(advertUuid), derivedUuidForm)
        }

        // --- what we listen on -----------------------------------------------
        //
        // Accepting and connecting both run for the window: whichever phone gets
        // there first drives, and the other answers.
        val rfcommServer = runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, HouseholdBeacon.SERVICE_UUID)
        }.getOrNull()
        val acceptingRfcomm = accept(rfcommServer, mine, received)

        val l2capServer = runCatching { adapter.listenUsingInsecureL2capChannel() }.getOrNull()
        val acceptingL2cap = accept(l2capServer, mine, received)
        // The PSM is only knowable once the socket exists, which is why the GATT
        // service that publishes it is opened here and not once at startup.
        val psm = l2capServer?.let { runCatching { it.psm }.getOrNull() }
        val directory = psm?.let { listening ->
            manager?.let { runCatching { PsmDirectory.open(context, it, advertUuid, listening) }.getOrNull() }
        }

        // --- what we look for ------------------------------------------------
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        val met: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val wanted = scanUuids.toSet()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                // The derived form is recognised by the UUID itself; the service
                // data one is the caller's rule, because that side accepts the
                // previous half-hour as well as this one and only it holds the
                // secret.
                val derived = record.serviceUuids?.any { wanted.contains(it.uuid) } == true
                val serviceData = record.getServiceData(ParcelUuid(HouseholdBeacon.SERVICE_UUID))
                    ?.let(isOurs) == true
                if (!derived && !serviceData) return
                if (!met.add(result.device.address)) return
                val device = result.device
                // A phone of this release advertises both forms, so which packet
                // arrives first decides which transport is used. Either carries
                // the same session, so that is a coin toss with nothing riding
                // on it — what matters is that a phone advertising *only* the
                // derived form is an iPhone or a newer Sprout, and the only
                // thing it can be dialled over is L2CAP.
                dialling.execute {
                    val reply = if (derived) {
                        connectOverL2cap(device, mine)
                    } else {
                        connectOverRfcomm(device, mine)
                    }
                    reply?.let { received += it }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // Silence here is how the first version of this hid a broken
                // window for a whole release: nothing advertised, nothing found,
                // and "nobody nearby" reported as though that were news.
                scanFailures += "scanning refused ($errorCode)"
            }
        }
        runCatching { scanner?.startScan(filters(scanUuids), scanSettings(), scanCallback) }

        try {
            delay(windowMs)
        } finally {
            runCatching { scanner?.stopScan(scanCallback) }
            runCatching { advertiser?.stopAdvertising(serviceDataForm) }
            runCatching { advertiser?.stopAdvertising(derivedUuidForm) }
            directory?.close()
            // Closing the server socket is what unblocks a thread parked in
            // accept(); interrupting one on its own would not.
            runCatching { rfcommServer?.close() }
            runCatching { l2capServer?.close() }
            acceptingRfcomm?.interrupt()
            acceptingL2cap?.interrupt()
            // A dial started just before the window closed is worth finishing:
            // the exchange itself takes well under a second once connected.
            dialling.shutdown()
            runCatching { dialling.awaitTermination(DIAL_GRACE_MS, TimeUnit.MILLISECONDS) }
            dialling.shutdownNow()
        }
        // A window in which the radio never really started is a failure, not an
        // empty room, and saying so is the difference between "nobody answered"
        // and "turn something back on". One advertisement of the two failing is
        // not that: the other form still reaches the phones that scan for it,
        // and a household that is merely half-visible for a window is worth less
        // noise than a wrong error.
        val advertisingProblems =
            if (advertisingForms.get() == 0) advertiseFailures.toList() else emptyList<String>()
        val problems = scanFailures.toList() + advertisingProblems
        if (received.isEmpty() && problems.isNotEmpty()) {
            throw NearbyTransport.RadioRefused(problems.joinToString("; "))
        }
        received.toList()
    }

    // --- listening -----------------------------------------------------------

    /** Answers whoever dials us, for as long as [server] is open. */
    @SuppressLint("MissingPermission") // same guard as exchange()
    private fun accept(
        server: BluetoothServerSocket?,
        mine: ByteArray,
        into: MutableList<ByteArray>,
    ): Thread? {
        if (server == null) return null
        return Thread {
            while (!Thread.currentThread().isInterrupted) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
                runCatching {
                    socket.use { into += SyncSession.exchange(it.inputStream, it.outputStream, mine) }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    // --- dialling ------------------------------------------------------------

    @SuppressLint("MissingPermission") // same guard as exchange()
    private fun connectOverRfcomm(device: BluetoothDevice, mine: ByteArray): ByteArray? {
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

    /**
     * The ADR-0016 path: ask over GATT which PSM to dial, then open the channel.
     *
     * Two round trips where RFCOMM needed none, which is the cost the ADR
     * accepts. Both are inside the ten-second window with room to spare.
     */
    @SuppressLint("MissingPermission") // same guard as exchange()
    private fun connectOverL2cap(device: BluetoothDevice, mine: ByteArray): ByteArray? {
        val psm = readPsm(device) ?: return null
        var socket: BluetoothSocket? = null
        return try {
            socket = device.createInsecureL2capChannel(psm)
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

    /**
     * Reads the listener's PSM from its GATT characteristic.
     *
     * The characteristic is looked up by its own UUID across every service the
     * other phone offers, rather than inside the service named by the
     * advertisement we matched. Both are the same service in practice, but a
     * phone can be found on the previous window's UUID and will by then be
     * advertising the current one, and losing an exchange to that is a bug
     * nobody would see happen.
     */
    @SuppressLint("MissingPermission") // same guard as exchange()
    private fun readPsm(device: BluetoothDevice): Int? {
        val answered = CountDownLatch(1)
        val psm = AtomicInteger(0)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED ->
                        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                            answered.countDown()
                        }
                    BluetoothProfile.STATE_DISCONNECTED -> answered.countDown()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.services
                    .flatMap { it.characteristics.orEmpty() }
                    .firstOrNull { it.uuid == L2capPsm.CHARACTERISTIC_UUID }
                if (characteristic == null ||
                    !runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
                ) {
                    answered.countDown()
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    L2capPsm.decode(value)?.let(psm::set)
                }
                answered.countDown()
            }

            /** What fires below API 33, where the four-argument form does not exist. */
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    L2capPsm.decode(characteristic.value)?.let(psm::set)
                }
                answered.countDown()
            }
        }

        val gatt = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull() ?: return null
        try {
            if (!answered.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        return psm.get().takeIf { it > 0 }
    }

    // --- what the packets look like ------------------------------------------

    private fun advertiseCallback(
        form: String,
        started: AtomicInteger,
        failures: MutableList<String>,
    ) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            started.incrementAndGet()
        }

        override fun onStartFailure(errorCode: Int) {
            failures += "$form refused ($errorCode)"
        }
    }

    /** The form every already-shipped Sprout looks for (ADR-0010). */
    private fun serviceDataAdvertisement(beacon: ByteArray): AdvertiseData = AdvertiseData.Builder()
        // The device name would defeat the point of a rotating beacon.
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceData(ParcelUuid(HouseholdBeacon.SERVICE_UUID), beacon)
        .build()

    /**
     * The form that replaces it (ADR-0016), and the only one an iPhone can send
     * or see. Nothing fixed goes out in it at all: the UUID *is* the rotating
     * value.
     */
    private fun derivedUuidAdvertisement(advertUuid: UUID): AdvertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(advertUuid))
        .build()

    private fun settings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
        .setConnectable(true)
        .build()

    /**
     * One filter per form, because the two live in different fields of a scan
     * record and a filter reads one field.
     *
     * The service-data clause is `setServiceData`, **not** `setServiceUuid`, and
     * the distinction is the whole reason nothing was ever found in the first
     * version: a filter's service-uuid clause is matched against the advertised
     * *list of service UUIDs* (AD types 0x02–0x07), while the beacon goes out as
     * *service data* (AD type 0x21), which the scan record parses into a
     * different field entirely. Empty data with an empty mask means "any value"
     * — the beacon itself is checked in code, where the previous half-hour is
     * accepted too.
     *
     * The derived form is the opposite case and wants exactly `setServiceUuid`,
     * once per window we will accept.
     */
    private fun filters(scanUuids: List<UUID>): List<ScanFilter> = buildList {
        add(
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(HouseholdBeacon.SERVICE_UUID), ByteArray(0), ByteArray(0))
                .build(),
        )
        scanUuids.forEach { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
    }

    private fun scanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    private fun hasPermissions(): Boolean = NearbyPermissions.granted(context)

    private companion object {
        const val SERVICE_NAME = "Sprout household sync"

        /** How long a dial already under way may finish after the window shuts. */
        const val DIAL_GRACE_MS = 5_000L

        /**
         * How long to wait for the PSM before giving up on a phone. Connecting,
         * discovering and one read is a second or two; anything past this is a
         * phone that walked away, and the window has other things to do.
         */
        const val GATT_TIMEOUT_MS = 6_000L
    }
}

/**
 * The GATT service that answers "which PSM are you listening on?" (ADR-0016).
 *
 * Its own UUID is the household's current advertised one, so the service a
 * caller was told about is the service it finds. The characteristic under it is
 * fixed and shared with iOS, and is readable without pairing or encryption —
 * what it discloses is a two-byte channel number on a phone the reader has
 * already proved it can recognise, and everything that then crosses that channel
 * is sealed.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class PsmDirectory private constructor(private val server: BluetoothGattServer) {

    @SuppressLint("MissingPermission") // the window checked NearbyPermissions before opening this
    fun close() {
        runCatching { server.clearServices() }
        runCatching { server.close() }
    }

    companion object {
        @SuppressLint("MissingPermission") // same guard as the window that opens it
        fun open(
            context: Context,
            manager: BluetoothManager,
            serviceUuid: UUID,
            psm: Int,
        ): PsmDirectory? {
            val value = L2capPsm.encode(psm)
            // The callback needs the server to answer with, and the server needs
            // the callback to be created — so it is handed over as soon as it
            // exists rather than captured, which cannot be done directly.
            val opened = AtomicReference<BluetoothGattServer?>(null)
            val callback = object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val server = opened.get() ?: return
                    val answer =
                        if (characteristic.uuid == L2capPsm.CHARACTERISTIC_UUID && offset in 0..value.size) {
                            value.copyOfRange(offset, value.size)
                        } else {
                            null
                        }
                    runCatching {
                        server.sendResponse(
                            device,
                            requestId,
                            if (answer == null) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS,
                            offset,
                            answer,
                        )
                    }
                }
            }

            val server = runCatching { manager.openGattServer(context, callback) }.getOrNull() ?: return null
            opened.set(server)

            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    L2capPsm.CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
            val added = runCatching { server.addService(service) }.getOrDefault(false)
            if (!added) {
                runCatching { server.close() }
                return null
            }
            return PsmDirectory(server)
        }
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
     *
     * ADR-0016 adds no permission to this list. A GATT server and an L2CAP
     * channel are both `BLUETOOTH_CONNECT`, which advertising and dialling
     * already needed.
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
