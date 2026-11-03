package me.suhaskalyan.photonsync

import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** The strip the user picked, plus any names they've given to strips. */
object SavedStrip {
    private const val PREFS = "photonsync"
    private const val KEY_ADDRESS = "strip_address"
    private const val KEY_NAME = "strip_name"
    private const val KEY_NICKNAME = "strip_nickname_"

    fun address(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun save(context: Context, address: String, name: String?) {
        prefs(context).edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
    }

    fun nickname(context: Context, address: String): String? = prefs(context).getString(KEY_NICKNAME + address, null)

    /** A blank name goes back to the strip's own name. Updates the live status if this strip is the saved one. */
    fun rename(context: Context, address: String, nickname: String, deviceName: String?) {
        val clean = nickname.trim().ifEmpty { null }
        prefs(context).edit().putString(KEY_NICKNAME + address, clean).apply()
        if (address == address(context)) {
            val shownName = clean ?: deviceName
            save(context, address, shownName)
            if (SyncState.stripName != null) {
                SyncState.stripName = shownName
                SyncState.notifyChanged()
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * "Find your strip": shows the nearest LED strips, and each row heats up from icy blue to hot red as you walk
 * towards that strip. Turn a strip off and it drops out, which settles which one is yours.
 */
@SuppressLint("MissingPermission", "SetTextI18n") // MainActivity grants the Bluetooth permissions before showing this
class StripPicker(
    activity: Activity,
    private val onPicked: (address: String, name: String?) -> Unit,
    private val onCancelled: () -> Unit,
) : GlassUi(activity) {
    companion object {
        private val NAME_KEYWORDS = listOf("elk", "lotus", "led", "bledom", "triones", "sinwe", "strip", "light")
        private const val NEAREST_LIMIT = 4
        private const val REFRESH_MS = 500L
        private const val RESORT_EVERY = 4 // re-order every 2s, so rows don't move while you reach for one
        private const val OUT_OF_RANGE_MS = 5000L
        private const val RSSI_SMOOTHING = 0.3f // single readings jump around by several dBm
        private const val COLDEST_RSSI = -90f
        private const val HOTTEST_RSSI = -40f
        private const val OFFLINE_RSSI = -200f
        private const val MAX_NAME_LENGTH = 24

        // Catppuccin, cold to hot
        private val HEAT_STOPS = intArrayOf(0xFF89B4FA.toInt(), 0xFF94E2D5.toInt(), 0xFFF9E2AF.toInt(), 0xFFFAB387.toInt(), 0xFFF38BA8.toInt())
    }

    private class Device(var name: String?, var rssi: Float, var lastSeen: Long) {
        val isLedStrip get() = name?.lowercase()?.let { n -> NAME_KEYWORDS.any { it in n } } == true
    }

    private class Row(
        val card: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val status: TextView,
        val signal: TextView,
        val barFill: View,
        val barEmpty: View,
    )

    private val scanner = activity.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val devices = HashMap<String, Device>()
    private val rows = HashMap<String, Row>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heat = ArgbEvaluator()
    private val savedAddress = SavedStrip.address(activity)

    private lateinit var list: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var toggle: TextView
    private var order = emptyList<String>()
    private var dialog: Dialog? = null
    private var scanning = false
    private var answered = false
    private var showAll = false
    private var ticks = 0

    private val refresh = object : Runnable {
        override fun run() {
            if (ticks++ % RESORT_EVERY == 0) reorder()
            updateRows()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    fun show() {
        if (savedAddress != null) {
            devices[savedAddress] = Device(SavedStrip.name(activity), OFFLINE_RSSI, 0L)
        }
        dialog = Dialog(activity).apply {
            setContentView(glassSheet(buildContent()))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnDismissListener {
                mainHandler.removeCallbacks(refresh)
                stopScan()
                if (!answered) onCancelled()
            }
            show()
        }
        startScan()
        mainHandler.post(refresh)
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    // -------------------------------------------------------------
    // LOOK
    // -------------------------------------------------------------
    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        root.addView(heading("FIND YOUR STRIP"))
        root.addView(text(13f, activity.getColor(R.color.text)).apply {
            text = "Walk towards a strip and it heats up"
            setPadding(0, dp(6), 0, dp(16))
        })

        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        emptyText = text(14f, activity.getColor(R.color.text)).apply {
            text = "Looking for LED strips nearby…\nMake sure yours is switched on."
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
        }
        val listHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(emptyText)
            addView(list)
        }
        root.addView(ScrollView(activity).apply {
            addView(listHolder)
            isFillViewport = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        toggle = label(11f, activity.getColor(R.color.subtext)).apply {
            text = "SHOW ALL DEVICES"
            setPadding(0, dp(12), dp(12), dp(12))
            setOnClickListener {
                showAll = !showAll
                this.text = if (showAll) "NEAREST STRIPS ONLY" else "SHOW ALL DEVICES"
                reorder()
                updateRows()
            }
        }
        footer.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(pillButton("CANCEL") { dismiss() })
        root.addView(footer)
        return root
    }

    private fun buildRow(address: String): Row {
        val titleView = text(17f, activity.getColor(R.color.text), bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView = text(11f, activity.getColor(R.color.subtext)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subtitleView)
        }
        val rename = label(10f, activity.getColor(R.color.subtext)).apply {
            text = "RENAME"
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener { showRename(address) }
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rename)
        }

        val statusView = label(11f, Color.WHITE)
        val signalView = label(11f, Color.WHITE)
        val readout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(8))
            addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(signalView)
        }
        val fill = View(activity)
        val empty = View(activity)
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.argb(36, 255, 255, 255), 2)
            clipToOutline = true
            addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
            addView(empty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            isClickable = true
            setOnClickListener { pick(address) }
            addView(top)
            addView(readout)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        }
        return Row(card, titleView, subtitleView, statusView, signalView, fill, empty)
    }

    /** 0 = ice cold, 1 = red hot. */
    private fun warmth(rssi: Float) = ((rssi - COLDEST_RSSI) / (HOTTEST_RSSI - COLDEST_RSSI)).coerceIn(0f, 1f)

    private fun heatColor(warmth: Float): Int {
        val position = warmth * (HEAT_STOPS.size - 1)
        val low = position.toInt().coerceAtMost(HEAT_STOPS.size - 2)
        return heat.evaluate(position - low, HEAT_STOPS[low], HEAT_STOPS[low + 1]) as Int
    }

    private fun heatLabel(rssi: Float) = when {
        rssi >= -55 -> "HOT · VERY CLOSE"
        rssi >= -70 -> "WARM · CLOSE"
        rssi >= -85 -> "COOL · FAR"
        else -> "COLD · VERY FAR"
    }

    private fun isOnline(address: String, device: Device) =
        isConnected(address) || SystemClock.uptimeMillis() - device.lastSeen <= OUT_OF_RANGE_MS

    /** A connected strip stops advertising, so it has no live signal to read. */
    private fun isConnected(address: String) = address == savedAddress && SyncState.stripName != null

    private fun displayName(address: String) =
        SavedStrip.nickname(activity, address) ?: devices[address]?.name ?: "Unknown device"

    // -------------------------------------------------------------
    // LIST
    // -------------------------------------------------------------
    private fun reorder() {
        val nearest = devices.entries
            .filter { (address, device) ->
                showAll || address == savedAddress || device.isLedStrip || SavedStrip.nickname(activity, address) != null
            }
            .filter { (address, device) -> address == savedAddress || isOnline(address, device) }
            .sortedByDescending { (address, device) -> if (isConnected(address)) Float.MAX_VALUE else device.rssi }
            .map { it.key }
        val next = if (showAll) nearest else nearest.take(NEAREST_LIMIT)
        if (next == order) return
        order = next
        list.removeAllViews()
        order.forEach { address ->
            val row = rows.getOrPut(address) { buildRow(address) }
            (row.card.parent as? ViewGroup)?.removeView(row.card)
            list.addView(row.card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun updateRows() {
        emptyText.visibility = if (order.isEmpty()) View.VISIBLE else View.GONE
        order.forEach { address ->
            val device = devices[address] ?: return@forEach
            val row = rows[address] ?: return@forEach
            val connected = isConnected(address)
            val online = isOnline(address, device)
            val warmth = if (connected) 1f else if (online) warmth(device.rssi) else 0f
            val color = if (connected) activity.getColor(R.color.green) else if (online) heatColor(warmth) else activity.getColor(R.color.surface0)

            row.title.text = displayName(address)
            val original = device.name?.takeIf { SavedStrip.nickname(activity, address) != null } ?: ""
            row.subtitle.text = listOf(original, address).filter { it.isNotEmpty() }.joinToString(" · ")
            row.status.text = when {
                connected -> "CONNECTED"
                !online -> "OFF OR OUT OF RANGE"
                else -> heatLabel(device.rssi)
            }
            row.signal.text = if (online && !connected) "${device.rssi.roundToInt()} dBm" else ""
            row.signal.setTextColor(color)
            row.status.setTextColor(if (online) color else activity.getColor(R.color.subtext))
            row.card.background = rounded(glassFill, 18, if (address == savedAddress || online) color else glassEdge)
            row.barFill.setBackgroundColor(color)
            val fillWeight = if (online) warmth.coerceAtLeast(0.04f) else 0f
            (row.barFill.layoutParams as LinearLayout.LayoutParams).weight = fillWeight
            (row.barEmpty.layoutParams as LinearLayout.LayoutParams).weight = 1f - fillWeight
            row.barFill.requestLayout()
        }
    }

    private fun pick(address: String) {
        answered = true
        val nickname = SavedStrip.nickname(activity, address)
        val name = nickname ?: devices[address]?.name
        dismiss()
        onPicked(address, name)
    }

    private fun showRename(address: String) {
        val popup = Dialog(activity)
        val field = EditText(activity).apply {
            setText(SavedStrip.nickname(activity, address) ?: "")
            hint = devices[address]?.name ?: "Name this strip"
            typeface = textFace
            textSize = 17f
            setTextColor(activity.getColor(R.color.text))
            setHintTextColor(activity.getColor(R.color.subtext))
            background = rounded(glassFill, 14, glassEdge)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))
            setSelection(text.length)
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
            addView(pillButton("CANCEL") { popup.dismiss() })
            addView(pillButton("SAVE", primary = true) {
                SavedStrip.rename(activity, address, field.text.toString(), devices[address]?.name)
                reorder()
                updateRows()
                popup.dismiss()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            addView(heading("RENAME STRIP"))
            addView(text(13f, activity.getColor(R.color.text)).apply {
                text = "Leave it empty to use the strip's own name."
                setPadding(0, dp(6), 0, dp(14))
            })
            addView(field)
            addView(buttons)
        }
        popup.setContentView(glassSheet(content))
        popup.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.show()
    }

    // -------------------------------------------------------------
    // SCAN
    // -------------------------------------------------------------
    private fun startScan() {
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner!!.startScan(null, settings, callback)
            scanning = true
        } catch (e: Exception) {
            SyncState.log("Bluetooth Adapter Error: ${e.message}")
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
            val wasOffline = device.lastSeen == 0L || now - device.lastSeen > OUT_OF_RANGE_MS
            device.rssi = if (wasOffline) result.rssi.toFloat() else device.rssi + RSSI_SMOOTHING * (result.rssi - device.rssi)
            device.lastSeen = now
        }

        override fun onScanFailed(errorCode: Int) {
            SyncState.log("BLE scan failed (code $errorCode). Open the list again to retry.")
            dismiss()
        }
    }
}
