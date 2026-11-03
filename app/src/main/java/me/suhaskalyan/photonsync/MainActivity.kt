package me.suhaskalyan.photonsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var micButton: Button
    private lateinit var autoResumeButton: Button
    private lateinit var statusText: TextView
    private lateinit var hexText: TextView
    private lateinit var swatch: GradientDrawable
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
        micButton = findViewById(R.id.mic_button)
        autoResumeButton = findViewById(R.id.auto_resume_button)
        statusText = findViewById(R.id.status)
        hexText = findViewById(R.id.hex)
        logText = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        swatch = rounded(Color.BLACK)
        findViewById<View>(R.id.swatch).background = swatch

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
        micButton.setOnClickListener { toggleMic() }
        autoResumeButton.setOnClickListener {
            if (noTapReady()) {
                SyncState.log("ℹ️ No-tap sync is already on.")
            } else if (Build.VERSION.SDK_INT >= 30) {
                showNoTapSetup()
            } else {
                // Too old for screenshots: the best on offer is the dialog opening by itself after unlock
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        findViewById<View>(R.id.status_card).setOnClickListener {
            if (SyncState.running && SyncState.capturePaused) startActivity(Intent(this, ResumeActivity::class.java))
        }

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
        syncButton.text = if (running) "⏹  STOP SCREEN SYNC" else "▶  START SCREEN SYNC"
        syncButton.background = rounded(getColor(if (running) R.color.red else R.color.green))

        micButton.text = if (SyncState.micOn) "🎙️  VOICE RECOGNITION: LISTENING" else "🎙️  VOICE RECOGNITION: OFF"
        micButton.background = rounded(getColor(if (SyncState.micOn) R.color.blue else R.color.surface0))
        micButton.setTextColor(getColor(if (SyncState.micOn) R.color.crust else R.color.text))
        micButton.alpha = if (running) 1f else 0.5f

        val savedName = SavedStrip.name(this) ?: SavedStrip.address(this)
        stripButton.text = if (savedName != null) "🔌  $savedName  ·  CHANGE" else "🔌  SELECT YOUR LED STRIP"
        stripButton.background = rounded(getColor(R.color.surface0))
        stripButton.setTextColor(getColor(R.color.text))

        val autoResume = noTapReady()
        autoResumeButton.text = if (autoResume) "🔓  NO-TAP SYNC AFTER UNLOCK: ON" else "🔓  NO-TAP SYNC AFTER UNLOCK: TAP TO SET UP"
        autoResumeButton.background = rounded(getColor(if (autoResume) R.color.mauve else R.color.surface0))
        autoResumeButton.setTextColor(getColor(if (autoResume) R.color.crust else R.color.text))

        statusText.text = when {
            !running -> "Idle"
            SyncState.capturePaused -> if (autoResume) "Paused · resumes on unlock" else "Paused · tap here to resume"
            strip != null -> "Linked: $strip"
            else -> "Waiting for ${savedName ?: "LED strip"}…"
        }
        val mode = when {
            !SyncState.powerOn -> "Power off"
            SyncState.screenOff -> "Screen off"
            SyncState.capturePaused && SyncState.syncMode -> "Phone was locked"
            SyncState.syncMode -> "Screen sync"
            else -> "Voice colour"
        }
        val color = if (running) SyncState.color else Color.BLACK
        swatch.setColor(color)
        hexText.text = if (running) "#%06X · %s".format(color and 0xFFFFFF, mode) else "Tap start to mirror your screen"

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

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = 12 * resources.displayMetrics.density
        setColor(color)
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
            SyncState.log("ℹ️ Start screen sync first, then turn on the mic.")
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
                    SyncState.log("⚠️ Microphone permission denied. Voice commands stay off.")
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
                    SyncState.log("⚠️ Bluetooth permission denied. PhotonSync can't reach your strip without it.")
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // granted in requestPermissionsThenStart
    private fun ensureBluetoothOn() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            SyncState.log("⚠️ This phone has no Bluetooth adapter.")
            return
        }
        if (Build.VERSION.SDK_INT in 28..30 && !getSystemService(LocationManager::class.java).isLocationEnabled) {
            SyncState.log("ℹ️ Turn on Location: Android 11 and older need it to scan for Bluetooth devices.")
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
                SyncState.log("📌 Strip selected: ${name ?: "Unknown device"} ($address)")
                if (SyncState.running) ScreenSyncService.retarget(this) else requestScreenCapture()
                render()
            },
            onCancelled = { changingStrip = false },
        ).also { it.show() }
    }

    private fun noTapReady() = ScreenColorService.isEnabled ||
        (Settings.canDrawOverlays(this) && ScreenSyncService.canCaptureWithoutPrompt(this))

    private fun showNoTapSetup() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Turn on no-tap sync")
            .setMessage(
                "Android asks for screen-share consent after every lock. To skip that, turn on " +
                    "\"${getString(R.string.screen_color_service_label)}\" in Accessibility settings.\n\n" +
                    "It only works out your screen's average colour. Nothing is saved or sent anywhere. " +
                    "Colours update about 3 times a second, a little less smooth than screen sharing.\n\n" +
                    "If it's greyed out: open App info for PhotonSync, tap ⋮, then Allow restricted settings."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Close", null)
            .show()
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
                if (resultCode == RESULT_OK) pickStripOrContinue() else SyncState.log("⚠️ Bluetooth must be on to reach your strip.")
            }
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    ScreenSyncService.start(this, resultCode, data)
                } else {
                    SyncState.log("⚠️ Screen capture permission denied.")
                }
            }
        }
    }
}
