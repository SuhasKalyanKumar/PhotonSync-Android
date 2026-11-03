package me.suhaskalyan.photonsync

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import kotlin.math.roundToInt

/** The strip the user picked. It is the only device PhotonSync connects to until they change it. */
object SavedStrip {
    private const val PREFS = "photonsync"
    private const val KEY_ADDRESS = "strip_address"
    private const val KEY_NAME = "strip_name"

    fun address(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun save(context: Context, address: String, name: String?) {
        prefs(context).edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Lists nearby LED strips for as long as it is open, with a live signal reading on each row:
 * walk towards a strip, or turn it off, to tell which one is yours.
 */
@SuppressLint("MissingPermission") // MainActivity grants the Bluetooth permissions before showing this
class StripPicker(
    private val activity: Activity,
    private val onPicked: (address: String, name: String?) -> Unit,
    private val onCancelled: () -> Unit,
) {
    companion object {
        private val NAME_KEYWORDS = listOf("elk", "lotus", "led", "bledom", "triones", "sinwe", "strip", "light")
        private const val REFRESH_MS = 500L
        private const val OUT_OF_RANGE_MS = 5000L
        private const val RSSI_SMOOTHING = 0.3f // single readings jump around by several dBm
    }

    private class Device(var name: String?, var rssi: Float, var lastSeen: Long) {
        val isLedStrip get() = name?.lowercase()?.let { n -> NAME_KEYWORDS.any { it in n } } == true
    }

    private val scanner = activity.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val devices = LinkedHashMap<String, Device>() // in the order they were first seen, so rows don't jump
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shown = emptyList<String>()
    private var dialog: AlertDialog? = null
    private var scanning = false
    private var answered = false
    private var showAll = false

    private val builder = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
    private val adapter = ArrayAdapter<String>(builder.context, android.R.layout.simple_list_item_1)

    private val refresh = object : Runnable {
        override fun run() {
            render()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    fun show() {
        val shownDialog = builder
            .setTitle("Select your LED strip (scanning…)")
            .setAdapter(adapter) { _, position ->
                answered = true
                val address = shown[position]
                onPicked(address, devices[address]?.name)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Show all devices", null)
            .setOnDismissListener {
                mainHandler.removeCallbacks(refresh)
                stopScan()
                if (!answered) onCancelled()
            }
            .show()
        dialog = shownDialog

        // For strips with unusual names. Set here because the builder's listener would close the dialog.
        val toggle = shownDialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        toggle.setOnClickListener {
            showAll = !showAll
            toggle.text = if (showAll) "LED strips only" else "Show all devices"
            render()
        }

        startScan()
        mainHandler.post(refresh)
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun startScan() {
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner!!.startScan(null, settings, callback)
            scanning = true
        } catch (e: Exception) {
            SyncState.log("⚠️ Bluetooth Adapter Error: ${e.message}")
            dismiss()
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }

    private fun render() {
        val visible = devices.filterValues { showAll || it.isLedStrip }
        // LED strips first, then named devices, nameless ones last. Signal strength doesn't move rows, so taps stay accurate.
        shown = visible.keys.sortedBy { address ->
            val device = visible.getValue(address)
            if (device.isLedStrip) 0 else if (device.name != null) 1 else 2
        }
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(shown.map { address ->
            val device = visible.getValue(address)
            "${device.name ?: "Unknown device"}\n${proximity(device)}\n$address"
        })
        adapter.notifyDataSetChanged()
    }

    private fun proximity(device: Device): String {
        if (SystemClock.uptimeMillis() - device.lastSeen > OUT_OF_RANGE_MS) return "○○○○  Out of range or off"
        val label = when {
            device.rssi >= -55 -> "●●●●  Very close"
            device.rssi >= -70 -> "●●●○  Close"
            device.rssi >= -85 -> "●●○○  Far"
            else -> "●○○○  Very far"
        }
        return "$label (${device.rssi.roundToInt()} dBm)"
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            val now = SystemClock.uptimeMillis()
            val device = devices[result.device.address]
            if (device == null) {
                devices[result.device.address] = Device(name, result.rssi.toFloat(), now)
                return
            }
            if (name != null) device.name = name
            device.rssi += RSSI_SMOOTHING * (result.rssi - device.rssi)
            device.lastSeen = now
        }

        override fun onScanFailed(errorCode: Int) {
            SyncState.log("⚠️ BLE scan failed (code $errorCode). Open the list again to retry.")
            dismiss()
        }
    }
}
