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
import java.util.ArrayDeque
import java.util.Calendar
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
    private val shouldBeOn: () -> Boolean,
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
        private const val COMMAND_SPACING_MS = 300L // ELK-BLEDDM drops a write that closely follows a command
        private const val TOO_DARK = 16

        // A new link is slow for its first moments, and early colours arrive in clumps. Starting the glide straight
        // away made it jump between colours before turning smooth, so the strip rests on the glide's first colour.
        private const val SECOND_POWER_MS = 300L
        private const val FIRST_COLOR_MS = 600L
        private const val LINK_SETTLE_MS = 900L

        // Firmwares differ in the second byte, so power commands go out in both variants
        private val POWER_ON = listOf(
            byteArrayOf(0x7e, 0x00, 0x04, 0xf0.toByte(), 0x00, 0x01, 0xff.toByte(), 0x00, 0xef.toByte()),
            byteArrayOf(0x7e, 0x04, 0x04, 0xf0.toByte(), 0x00, 0x01, 0xff.toByte(), 0x00, 0xef.toByte()),
        )
        private val POWER_OFF = listOf(
            byteArrayOf(0x7e, 0x00, 0x04, 0x00, 0x00, 0x00, 0xff.toByte(), 0x00, 0xef.toByte()),
            byteArrayOf(0x7e, 0x04, 0x04, 0x00, 0x00, 0x00, 0xff.toByte(), 0x00, 0xef.toByte()),
        )

        // Tested on an ELK-BLEDDM: fires with nothing connected, and the disabled form cancels it
        private const val ALL_DAYS = 0x7f
        private const val TIMER_ENABLED = 0x80

        private fun clockFrame(now: Calendar): ByteArray {
            val mondayFirstDay = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            return byteArrayOf(
                0x7e, 0x00, 0x83.toByte(), now.get(Calendar.HOUR_OF_DAY).toByte(), now.get(Calendar.MINUTE).toByte(),
                now.get(Calendar.SECOND).toByte(), mondayFirstDay.toByte(), 0x00, 0xef.toByte(),
            )
        }

        private fun timerFrame(turnOn: Boolean, enabled: Boolean, hour: Int, minute: Int): ByteArray {
            val days = if (enabled) ALL_DAYS or TIMER_ENABLED else ALL_DAYS
            return byteArrayOf(
                0x7e, 0x00, 0x82.toByte(), (if (enabled) hour else 0).toByte(), (if (enabled) minute else 0).toByte(), 0x00,
                (if (turnOn) 0x00 else 0x01).toByte(), days.toByte(), 0xef.toByte(),
            )
        }
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
    private val commands = ArrayDeque<ByteArray>() // these must arrive, unlike colours, and go first
    private var holdUntil = 0L
    private var lastColorDark = false

    var isReady = false
        private set
    private var settling = false // a new link only takes power commands until it has settled

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
            powerOffThen { if (!closed) { close(); scan() } }
        } else {
            close()
            scan()
        }
    }

    fun sendColor(r: Int, g: Int, b: Int) {
        if (closed || settling) return
        queue(r, g, b)
    }

    /** The real power switch, not a black colour: the strip keeps its colour for when a timer turns it back on. */
    fun setPower(on: Boolean) {
        if (closed || !isReady) return
        commands.addAll(if (on) POWER_ON else POWER_OFF)
        flush()
    }

    /** Sets the strip's clock and writes its timers. Returns false if there is no link right now. */
    fun applyTimer(timer: StripTimer): Boolean {
        if (closed || !isReady) return false
        commands.add(clockFrame(Calendar.getInstance()))
        commands.add(timerFrame(turnOn = true, timer.onEnabled, timer.onHour, timer.onMinute))
        commands.add(timerFrame(turnOn = false, timer.offEnabled, timer.offHour, timer.offMinute))
        flush()
        return true
    }

    private fun powerOffThen(done: () -> Unit) {
        commands.clear()
        // A black last colour would make a morning timer "turn on" to nothing
        val delay = if (lastColorDark) {
            writeRaw(byteArrayOf(0x7e, 0x07, 0x05, 0x03, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00, 0xef.toByte()))
            150L
        } else {
            0L
        }
        handler.postDelayed({ writeRaw(POWER_OFF[0]) }, delay)
        handler.postDelayed({ writeRaw(POWER_OFF[1]) }, delay + 150)
        handler.postDelayed(done, delay + 350)
    }

    /** Turns the strip off, then drops the link. [onDone] runs on the handler thread once closed. */
    fun shutdown(onDone: () -> Unit) {
        closed = true
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(connectTimeout)
        stopScan()
        if (isReady) {
            powerOffThen { close(); onDone() }
        } else {
            close()
            onDone()
        }
    }

    private fun queue(r: Int, g: Int, b: Int) {
        lastColorDark = maxOf(r, g, b) < TOO_DARK
        pending = byteArrayOf(0x7e, 0x07, 0x05, 0x03, r.toByte(), g.toByte(), b.toByte(), 0x00, 0xef.toByte())
        flush()
    }

    /** Sends a one-off command right away instead of waiting behind colour packets. */
    private fun writeRaw(payload: ByteArray) {
        if (!isReady) return
        writeInFlightSince = 0L
        holdUntil = 0L
        pending = payload
        flush()
    }

    /** Sends the pending packet if the link is free. Called on every tick so a dropped write can't strand it. */
    fun flush() {
        val g = gatt ?: return
        val ch = characteristic ?: return
        val now = SystemClock.uptimeMillis()
        if (now < holdUntil) return
        if (writeInFlightSince != 0L && now - writeInFlightSince < WRITE_TIMEOUT_MS) return

        val command = commands.peekFirst()
        val payload = command ?: pending ?: return
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            writeLegacy(g, ch, payload)
        }
        if (accepted) {
            if (command != null) {
                commands.pollFirst()
                holdUntil = now + COMMAND_SPACING_MS
            } else {
                pending = null
            }
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
            SyncState.log("Waiting for ${targetName ?: targetAddress}. It connects as soon as the strip is on.")
        }
        handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
    }

    private val connectTimeout = Runnable {
        SyncState.log("Connection timed out. Retrying in 3s...")
        dropLinkAndRetry()
    }

    private fun scan() {
        val address = targetAddress
        if (closed || address == null) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            SyncState.log("Bluetooth is off. Retrying in 5s...")
            handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
            return
        }

        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            SyncState.log("Bluetooth Adapter Error: ${e.message}")
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
                if (scanning && result.device.address == targetAddress) connect(result.device, targetName ?: name ?: "LED strip")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                stopScan()
                SyncState.log("BLE scan failed (code $errorCode). Retrying in 5s...")
                handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
            }
        }
    }

    // -------------------------------------------------------------
    // CONNECTION ENGINE
    // -------------------------------------------------------------
    private fun connect(device: BluetoothDevice, name: String) {
        stopScan()
        SyncState.log("Found $name (${device.address}). Connecting...")
        targetName = name
        gatt = device.connectGatt(
            context, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun close() {
        isReady = false
        settling = false
        characteristic = null
        pending = null
        commands.clear()
        holdUntil = 0L
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
                if (!closed) SyncState.log("Link disrupted (status $status). Reconnecting in 3s...")
                dropLinkAndRetry()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            handler.removeCallbacks(connectTimeout)
            val ch = if (status == BluetoothGatt.GATT_SUCCESS) findWritableCharacteristic(g) else null
            if (ch == null) {
                SyncState.log("No writable characteristic on this device. Retrying in 3s...")
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
            settling = true
            waitLogged = false
            SyncState.stripName = targetName
            SyncState.log("Characteristic Resolved: ${ch.uuid}")
            SyncState.log("Hardware linked successfully!")

            // Colour packets are ignored while the controller is switched off (remote / timer / last session), so
            // wake it first. A link that comes back while the phone sleeps must not light the room up, though.
            val power = if (shouldBeOn()) POWER_ON else POWER_OFF
            writeRaw(power[0])
            handler.postDelayed({ writeRaw(power[1]) }, SECOND_POWER_MS)
            handler.postDelayed({
                if (isReady && shouldBeOn()) queue(255, 0, 0)
            }, FIRST_COLOR_MS)
            handler.postDelayed({
                settling = false
                if (isReady) {
                    onReady()
                }
            }, LINK_SETTLE_MS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeInFlightSince = 0L
            flush()
        }
    }
}
