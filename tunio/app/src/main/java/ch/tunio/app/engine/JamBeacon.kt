package ch.tunio.app.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Finding Jams nearby over Bluetooth (works on mobile data or another Wi-Fi too, within ~10 m).
 * The host's phone announces a Tunio Jam service; a phone nearby connects for a moment and reads how to join
 * (server address, Jam id, name, password or not). The queue and sync still go through the home server.
 * Same service as the iPhone app, so iPhones and Android phones find each other.
 */
@SuppressLint("MissingPermission")
object JamBeacon {
    private const val TAG = "TunioJamBeacon"
    val SERVICE: UUID = UUID.fromString("7d2ea1c0-5c4b-4f1e-9a43-7e1a0b3c6a10")
    val INFO: UUID = UUID.fromString("7d2ea1c0-5c4b-4f1e-9a43-7e1a0b3c6a11")

    private var server: BluetoothGattServer? = null
    private var advertising: AdvertiseCallback? = null
    @Volatile private var payload = ByteArray(0)

    /** What has to be allowed for Bluetooth (asked when the Jam sheet opens). */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(ctx: Context) = permissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun manager(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)

    // ---------------------------------------------------------------- the host announces its Jam

    fun startHosting(ctx: Context, serverUrl: String, id: String, name: String, locked: Boolean, host: String) {
        val json = buildJsonObject {
            put("u", serverUrl); put("j", id); put("n", name.take(40)); put("l", locked); put("h", host.take(30))
        }
        payload = json.toString().toByteArray()
        if (server != null || !allowed(ctx)) return
        val bm = manager(ctx) ?: return
        val adapter = bm.adapter ?: return
        if (!adapter.isEnabled) return
        runCatching {
            val gatt = bm.openGattServer(ctx, object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
                    val data = payload
                    val part = if (offset < data.size) data.copyOfRange(offset, data.size) else ByteArray(0)
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
                }
            }) ?: return
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            gatt.addService(service)
            server = gatt
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertising failed: $errorCode") }
            }
            adapter.bluetoothLeAdvertiser?.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),
                cb,
            )
            advertising = cb
        }.onFailure { Log.w(TAG, "can't announce the Jam", it) }
    }

    fun stopHosting(ctx: Context) {
        runCatching {
            advertising?.let { manager(ctx)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
            server?.close()
        }
        advertising = null
        server = null
    }

    // ---------------------------------------------------------------- phones nearby look for Jams

    /** Jams announced by phones nearby (scans for a few seconds, then reads each one). */
    suspend fun scan(ctx: Context, scanMs: Long = 4000): List<JamFound> {
        if (!allowed(ctx)) return emptyList()
        val adapter = manager(ctx)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val devices = LinkedHashMap<String, BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(devices) { devices.putIfAbsent(result.device.address, result.device) }
            }
        }
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb,
            )
        }.onFailure { return emptyList() }
        delay(scanMs)
        runCatching { scanner.stopScan(cb) }
        val found = synchronized(devices) { devices.values.toList() }
        return found.take(8).mapNotNull { d -> withTimeoutOrNull(6000) { read(ctx, d) } }.distinctBy { it.info.id }
    }

    /** Connects to one phone for a moment and reads its Jam. */
    private suspend fun read(ctx: Context, device: BluetoothDevice): JamFound? = suspendCancellableCoroutine { cont ->
        var done = false
        fun finish(gatt: BluetoothGatt?, r: JamFound?) {
            if (done) return
            done = true
            runCatching { gatt?.disconnect(); gatt?.close() }
            if (cont.isActive) cont.resume(r)
        }
        val gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish(g, null)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(SERVICE)?.getCharacteristic(INFO) ?: return finish(g, null)
                if (!g.readCharacteristic(c)) finish(g, null)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                finish(g, if (status == BluetoothGatt.GATT_SUCCESS) parse(c.value) else null)
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                finish(g, if (status == BluetoothGatt.GATT_SUCCESS) parse(value) else null)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
    }

    fun parse(bytes: ByteArray?): JamFound? = runCatching {
        val o = Parsing.json.parseToJsonElement(String(bytes ?: return null)) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val server = s("u").trimEnd('/')
        val id = s("j")
        if (server.isEmpty() || id.isEmpty()) return null
        JamFound(server, JamInfo(id, s("n").ifBlank { "Jam" }, (o["l"] as? JsonPrimitive)?.booleanOrNull ?: false, s("h"), 0))
    }.getOrNull()
}
