package me.suhaskalyan.photonsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val EXTRA_ENABLE_MIC = "enable_mic"
        private const val REQ_PERMISSIONS = 1
        private const val REQ_ENABLE_BT = 2
        private const val REQ_CAPTURE = 3
        private const val REQ_MIC = 4
        private const val PREFS = "photonsync"
        private const val KEY_BRIGHTNESS = "brightness"
    }

    private lateinit var syncButton: Button
    private lateinit var stripButton: Button
    private lateinit var timerButton: Button
    private lateinit var micButton: Button
    private lateinit var statusText: TextView
    private lateinit var hexText: TextView
    private lateinit var orb: OrbView
    private lateinit var aurora: AuroraView
    private lateinit var slider: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var shownLogVersion = -1
    private var picker: StripPicker? = null
    private var changingStrip = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        syncButton = findViewById(R.id.sync_button)
        stripButton = findViewById(R.id.strip_button)
        timerButton = findViewById(R.id.timer_button)
        micButton = findViewById(R.id.mic_button)
        statusText = findViewById(R.id.status)
        hexText = findViewById(R.id.hex)
        logText = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        orb = findViewById(R.id.orb)
        aurora = findViewById(R.id.aurora)
        val details = findViewById<View>(R.id.details)
        findViewById<View>(R.id.details_toggle).setOnClickListener {
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // MASTER BRIGHTNESS
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        brightnessValue = findViewById(R.id.brightness_value)
        slider = findViewById(R.id.brightness)
        if (!SyncState.running) SyncState.brightness = prefs.getInt(KEY_BRIGHTNESS, 100) / 100f
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) SyncState.brightness = progress / 100f
                brightnessValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {
                prefs.edit().putInt(KEY_BRIGHTNESS, bar.progress).apply()
            }
        })

        syncButton.setOnClickListener {
            if (SyncState.running) {
                stopService(Intent(this, ScreenSyncService::class.java))
            } else {
                requestPermissionsThenStart()
            }
        }
        stripButton.setOnClickListener {
            changingStrip = true
            requestPermissionsThenStart()
        }
        timerButton.setOnClickListener {
            showTimerDialog(this) {
                if (SyncState.running) {
                    ScreenSyncService.applyTimer(this)
                } else {
                    SyncState.log("Timer saved. Start sync once to send it to your strip.")
                }
                render()
            }
        }
        micButton.setOnClickListener { toggleMic() }

        handleMicExtra(intent)

        // A strip was picked before, so opening the app is enough to get it going again
        if (savedInstanceState == null && !SyncState.running && SavedStrip.address(this) != null) {
            requestPermissionsThenStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMicExtra(intent)
    }

    override fun onStart() {
        super.onStart()
        SyncState.addListener(listener)
        if (SyncState.running && SyncState.capturePaused && !ScreenColorService.isEnabled) {
            startActivity(Intent(this, ResumeActivity::class.java))
        }
        render()
    }

    override fun onStop() {
        picker?.dismiss()
        SyncState.removeListener(listener)
        super.onStop()
    }

    private fun render() {
        val running = SyncState.running
        val strip = SyncState.stripName
        syncButton.text = if (running) "STOP" else "START"
        syncButton.background = if (running) glass(getColor(R.color.red)) else pill(getColor(R.color.mauve))
        syncButton.setTextColor(getColor(if (running) R.color.text else R.color.crust))

        micButton.text = if (SyncState.micOn) "Voice  ·  listening" else "Voice  ·  off"
        micButton.background = glass(if (SyncState.micOn) getColor(R.color.blue) else null)
        micButton.setTextColor(getColor(R.color.text))
        micButton.alpha = if (running) 1f else 0.5f

        val savedName = SavedStrip.name(this) ?: SavedStrip.address(this)
        stripButton.text = if (savedName != null) "$savedName  ·  change" else "Select your LED strip"
        stripButton.background = glass(null)
        stripButton.setTextColor(getColor(R.color.text))

        timerButton.text = "Timer  ·  ${StripTimer.load(this).summary}"
        timerButton.background = glass(null)
        timerButton.setTextColor(getColor(R.color.text))


        statusText.text = when {
            !running -> "Idle"
            SyncState.capturePaused -> "Rainbow · allow sharing to sync"
            strip != null -> "Linked: $strip"
            else -> "Waiting for ${savedName ?: "LED strip"}…"
        }
        val mode = when {
            !SyncState.powerOn -> "Power off"
            SyncState.locked -> "Rainbow while locked"
            SyncState.capturePaused && SyncState.syncMode -> "Rainbow"
            SyncState.rainbow -> "Rainbow"
            SyncState.syncMode -> "Screen sync"
            else -> "Voice colour"
        }
        val color = if (running) SyncState.color else Color.BLACK
        orb.setAccent(color)
        aurora.setAccent(color)
        hexText.text = if (running) "#%06X  ·  %s".format(color and 0xFFFFFF, mode) else "Ready when you are"

        // Voice "low / medium / high" moves the slider too
        val percent = (SyncState.brightness * 100).roundToInt()
        if (slider.progress != percent) slider.progress = percent
        brightnessValue.text = "$percent%"

        if (SyncState.logVersion != shownLogVersion) {
            shownLogVersion = SyncState.logVersion
            logText.text = SyncState.logText()
            logScroll.post { logScroll.scrollTo(0, logText.height) }
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = 100 * resources.displayMetrics.density
        setColor(color)
    }

    /** See-through with a hairline edge, so the moving colours show behind it. */
    private fun glass(edge: Int?) = GradientDrawable().apply {
        cornerRadius = 100 * resources.displayMetrics.density
        setColor(Color.argb(26, 255, 255, 255))
        setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), edge ?: Color.argb(46, 255, 255, 255))
    }

    // -------------------------------------------------------------
    // MICROPHONE TOGGLE (in-app button + notification button without permission yet)
    // -------------------------------------------------------------
    private fun handleMicExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ENABLE_MIC, false) != true) return
        intent.removeExtra(EXTRA_ENABLE_MIC)
        if (!SyncState.micOn) toggleMic()
    }

    private fun toggleMic() {
        if (!SyncState.running) {
            SyncState.log("Start screen sync first, then turn on the mic.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        ScreenSyncService.toggleMic(this)
    }

    // -------------------------------------------------------------
    // START FLOW: permissions -> Bluetooth on -> pick strip (first time) -> screen capture consent -> service
    // -------------------------------------------------------------
    private fun requestPermissionsThenStart() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isEmpty()) ensureBluetoothOn() else requestPermissions(wanted.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQ_MIC -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    ScreenSyncService.toggleMic(this)
                } else {
                    SyncState.log("Microphone permission denied. Voice commands stay off.")
                }
            }
            REQ_PERMISSIONS -> {
                // The notification permission is nice to have; the Bluetooth ones are required
                val denied = permissions.filterIndexed { i, permission ->
                    permission != Manifest.permission.POST_NOTIFICATIONS &&
                        grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
                }
                if (denied.isEmpty()) {
                    ensureBluetoothOn()
                } else {
                    SyncState.log("Bluetooth permission denied. PhotonSync can't reach your strip without it.")
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // granted in requestPermissionsThenStart
    private fun ensureBluetoothOn() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            SyncState.log("This phone has no Bluetooth adapter.")
            return
        }
        if (Build.VERSION.SDK_INT in 28..30 && !getSystemService(LocationManager::class.java).isLocationEnabled) {
            SyncState.log("Turn on Location: Android 11 and older need it to scan for Bluetooth devices.")
        }
        if (adapter.isEnabled) {
            pickStripOrContinue()
        } else {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
        }
    }

    private fun pickStripOrContinue() {
        if (!changingStrip && SavedStrip.address(this) != null) {
            requestScreenCapture()
            return
        }
        picker = StripPicker(
            this,
            onPicked = { address, name ->
                changingStrip = false
                SavedStrip.save(this, address, name)
                SyncState.log("Strip selected: ${name ?: "Unknown device"} ($address)")
                if (SyncState.running) ScreenSyncService.retarget(this) else requestScreenCapture()
                render()
            },
            onCancelled = { changingStrip = false },
        ).also { it.show() }
    }

    private fun requestScreenCapture() {
        if (ScreenColorService.isEnabled) {
            ScreenSyncService.startWithScreenshots(this)
            return
        }
        startActivityForResult(ScreenSyncService.captureIntent(this), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ENABLE_BT -> {
                if (resultCode == RESULT_OK) pickStripOrContinue() else SyncState.log("Bluetooth must be on to reach your strip.")
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    ScreenSyncService.start(this, resultCode, data)
                } else {
                    SyncState.log("Screen capture permission denied.")
                }
            }
        }
    }
}
