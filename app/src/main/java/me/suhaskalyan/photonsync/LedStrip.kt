package me.suhaskalyan.photonsync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.UUID

/**
 * Connects to the LED controller the user picked, keeps the link alive and writes colour packets to it.
 * Every method and every GATT callback runs on [handler]'s thread, so no locking is needed.
 */
@SuppressLint("MissingPermission") // MainActivity grants the Bluetooth permissions before the service starts
class LedStrip(
    private val context: Context,
    private val handler: Handler,
    private val onReady: () -> Unit,
) {
    companion object {
        private val FALLBACK_CHARACTERISTIC_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
        private val GENERIC_SERVICES = setOf(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"), // Generic Attribute
        )
        private const val SCAN_MS = 4000L
        private const val RESCAN_DELAY_MS = 5000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val WRITE_TIMEOUT_MS = 250L
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private var targetAddress: String? = null
    private var targetName: String? = null
    private var waitLogged = false
    private var scanning = false
    private var closed = false

    // Only the newest colour matters, so a busy link just overwrites the pending packet
    private var pending: ByteArray? = null
    private var writeInFlightSince = 0L

    var isReady = false
        private set

    fun start() {
        targetAddress = SavedStrip.address(context)
        targetName = SavedStrip.name(context)
        scan()
    }

    /** Switches to a newly picked strip, leaving the old one dark. */
    fun retarget(address: String, name: String?) {
        if (closed) return
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(connectTimeout)
        stopScan()
        targetAddress = address
        targetName = name
        waitLogged = false
        if (isReady) {
            writeInFlightSince = 0L
            queue(0, 0, 0)
            isReady = false
            handler.postDelayed({ close(); scan() }, 200)
        } else {
            close()
            scan()
        }
    }

    fun sendColor(r: Int, g: Int, b: Int) {
        if (closed) return
        queue(r, g, b)
    }

    /** Turns the strip off, then drops the link. [onDone] runs on the handler thread once closed. */
    fun shutdown(onDone: () -> Unit) {
        closed = true
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(connectTimeout)
        stopScan()
        if (isReady) {
            writeInFlightSince = 0L
            queue(0, 0, 0)
            handler.postDelayed({ close(); onDone() }, 200)
        } else {
            close()
            onDone()
        }
    }

    private fun queue(r: Int, g: Int, b: Int) {
        pending = byteArrayOf(0x7e, 0x07, 0x05, 0x03, r.toByte(), g.toByte(), b.toByte(), 0x00, 0xef.toByte())
        flush()
    }

    /** Sends a one-off command right away instead of waiting behind colour packets. */
    private fun writeRaw(payload: ByteArray) {
        if (!isReady) return
        writeInFlightSince = 0L
        pending = payload
        flush()
    }

    /** Sends the pending packet if the link is free. Called on every tick so a dropped write can't strand it. */
    fun flush() {
        val g = gatt ?: return
        val ch = characteristic ?: return
        val payload = pending ?: return
        val now = SystemClock.uptimeMillis()
        if (writeInFlightSince != 0L && now - writeInFlightSince < WRITE_TIMEOUT_MS) return

        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            writeLegacy(g, ch, payload)
        }
        if (accepted) {
            pending = null
            writeInFlightSince = now
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(g: BluetoothGatt, ch: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        ch.writeType = writeType
        ch.value = payload
        return g.writeCharacteristic(ch)
    }

    // -------------------------------------------------------------
    // BLE SCAN (only for the saved strip)
    // -------------------------------------------------------------
    private val scanRunnable = Runnable { scan() }

    private val scanTimeout = Runnable {
        stopScan()
        if (!waitLogged) {
            waitLogged = true
            SyncState.log("🔍 Waiting for ${targetName ?: targetAddress}. It connects as soon as the strip is on.")
        }
        handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
    }

    private val connectTimeout = Runnable {
        SyncState.log("⚠️ Connection timed out. Retrying in 3s...")
        dropLinkAndRetry()
    }

    private fun scan() {
        val address = targetAddress
        if (closed || address == null) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            SyncState.log("⚠️ Bluetooth is off. Retrying in 5s...")
            handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
            return
        }

        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            SyncState.log("⚠️ Bluetooth Adapter Error: ${e.message}")
            handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
            return
        }
        scanning = true
        handler.postDelayed(scanTimeout, SCAN_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            handler.post {
                if (scanning && result.device.address == targetAddress) connect(result.device, name ?: targetName ?: "LED strip")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                stopScan()
                SyncState.log("⚠️ BLE scan failed (code $errorCode). Retrying in 5s...")
                handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
            }
        }
    }

    // -------------------------------------------------------------
    // CONNECTION ENGINE
    // -------------------------------------------------------------
    private fun connect(device: BluetoothDevice, name: String) {
        stopScan()
        SyncState.log("✅ Found $name (${device.address}). Connecting...")
        targetName = name
        gatt = device.connectGatt(
            context, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun close() {
        isReady = false
        characteristic = null
        pending = null
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        SyncState.stripName = null
        SyncState.notifyChanged()
    }

    private fun dropLinkAndRetry() {
        handler.removeCallbacks(connectTimeout)
        close()
        if (!closed) handler.postDelayed(scanRunnable, RECONNECT_DELAY_MS)
    }

    /** Prefers the usual fff3 LED characteristic, else the first writable one outside the generic services. */
    private fun findWritableCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val candidates = g.services
            .filter { it.uuid !in GENERIC_SERVICES }
            .flatMap { it.characteristics }
            .filter { (it.properties and writable) != 0 }
        return candidates.firstOrNull { it.uuid == FALLBACK_CHARACTERISTIC_UUID } ?: candidates.firstOrNull()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                if (!closed) SyncState.log("⚠️ Link disrupted (status $status). Reconnecting in 3s...")
                dropLinkAndRetry()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            handler.removeCallbacks(connectTimeout)
            val ch = if (status == BluetoothGatt.GATT_SUCCESS) findWritableCharacteristic(g) else null
            if (ch == null) {
                SyncState.log("⚠️ No writable characteristic on this device. Retrying in 3s...")
                dropLinkAndRetry()
                return
            }

            characteristic = ch
            writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            pending = null
            writeInFlightSince = 0L
            isReady = true
            waitLogged = false
            SyncState.stripName = targetName
            SyncState.log("🎯 Characteristic Resolved: ${ch.uuid}")
            SyncState.log("⚡ Hardware linked successfully!")

            // Colour packets are ignored while the controller is switched off (remote / vendor app),
            // so wake it first. BLEDOM firmwares differ in the second byte, so send both variants.
            writeRaw(byteArrayOf(0x7e, 0x00, 0x04, 0xf0.toByte(), 0x00, 0x01, 0xff.toByte(), 0x00, 0xef.toByte()))
            handler.postDelayed({
                writeRaw(byteArrayOf(0x7e, 0x04, 0x04, 0xf0.toByte(), 0x00, 0x01, 0xff.toByte(), 0x00, 0xef.toByte()))
            }, 100)
            handler.postDelayed({
                if (isReady) {
                    SyncState.log("💡 Sent power-on to strip")
                    onReady()
                }
            }, 250)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeInFlightSince = 0L
            flush()
        }
    }
}
