package me.suhaskalyan.photonsync

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.roundToInt

/**
 * Mirrors the screen into a tiny virtual display (the GPU does the downscaling for us),
 * averages its colour and streams it to the LED strip, like screen_sync_loop on desktop.
 */
class ScreenSyncService : Service() {

    companion object {
        private const val ACTION_STOP = "me.suhaskalyan.photonsync.STOP"
        private const val ACTION_TOGGLE_MIC = "me.suhaskalyan.photonsync.TOGGLE_MIC"
        private const val ACTION_RETARGET = "me.suhaskalyan.photonsync.RETARGET"
        private const val ACTION_APPLY_TIMER = "me.suhaskalyan.photonsync.APPLY_TIMER"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SCREENSHOTS = "screenshots"
        private const val CHANNEL_ID = "screen_sync"
        private const val NOTIFICATION_ID = 1

        private const val CAPTURE_LONG_SIDE = 120 // about what the desktop samples (every 16th pixel of 1080p)
        private const val TX_INTERVAL_MS = 20L
        private const val SMOOTHING = 0.75f
        private const val SCREENSHOT_INTERVAL_MS = 340L // Android refuses shots closer together than 333ms
        private const val UI_INTERVAL_MS = 100L
        private const val VOICE_COOLDOWN_MS = 1000L
        private const val SCREEN_OFF_GRACE_MS = 3000L // a share can end just after the screen wakes back up
        private const val POWER_OFF_AWAKE_MS = 3000L // long enough for an outro and both power-off commands
        private const val TOO_DARK = 16f
        private const val WAKE_FALLBACK_LEVEL = 128f // shown on wake when the last screen colour was black

        // Startup glide: red, orange, yellow, green, blue, indigo, violet
        private val INTRO_COLORS = listOf(
            intArrayOf(255, 0, 0), intArrayOf(255, 110, 0), intArrayOf(255, 220, 0), intArrayOf(0, 255, 0),
            intArrayOf(0, 0, 255), intArrayOf(75, 0, 130), intArrayOf(238, 130, 238),
        )
        private const val INTRO_STEPS_PER_COLOR = 10
        private const val ANIMATION_STEP_MS = 30L
        private const val RAINBOW_STEPS_PER_COLOR = 100
        private const val RAINBOW_STEP_MS = 50L // 5s from one colour to the next, slow enough to be calming
        private const val WAKE_COMMANDS_MS = 650L // colours sent while the power commands land would be lost

        /** Starts sync, or resumes it after a lock with a fresh screen-capture consent. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenSyncService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /** Starts sync with accessibility screenshots: no consent dialog now or after any unlock. */
        fun startWithScreenshots(context: Context) {
            context.startForegroundService(
                Intent(context, ScreenSyncService::class.java).putExtra(EXTRA_SCREENSHOTS, true)
            )
        }

        /** Android 14+: asking for the whole display up front skips the "one app or entire screen?" picker. */
        fun captureIntent(context: Context): Intent {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        }

        /**
         * True once `adb shell appops set <package> PROJECT_MEDIA allow` has been run: Android then approves
         * screen capture without showing its "Start sharing?" dialog. Apps can't grant this to themselves.
         */
        @Suppress("DEPRECATION")
        fun canCaptureWithoutPrompt(context: Context): Boolean =
            context.getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow("android:project_media", Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

        /** Tells a running service to switch to the strip that was just saved. */
        fun retarget(context: Context) {
            context.startService(Intent(context, ScreenSyncService::class.java).setAction(ACTION_RETARGET))
        }

        /** Sends the saved timer to the strip now, if sync is running. */
        fun applyTimer(context: Context) {
            context.startService(Intent(context, ScreenSyncService::class.java).setAction(ACTION_APPLY_TIMER))
        }

        fun toggleMic(context: Context) {
            context.startService(Intent(context, ScreenSyncService::class.java).setAction(ACTION_TOGGLE_MIC))
        }
    }

    private val thread = HandlerThread("PhotonSync")
    private lateinit var handler: Handler
    private lateinit var strip: LedStrip
    private lateinit var voice: VoiceEngine

    // Everything below is only touched on the handler thread (projection is also set on main)
    @Volatile private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0

    private val target = FloatArray(3)
    private val current = FloatArray(3)
    private val lastSent = IntArray(3) { -1 }
    private var animating = false
    private var animationId = 0
    private val rainbowFrames by lazy { glide(INTRO_COLORS + listOf(INTRO_COLORS.first()), RAINBOW_STEPS_PER_COLOR) }
    private var stripPoweredOn = true
    private var lastUiUpdate = 0L
    private val staticColor = intArrayOf(255, 255, 255)
    private val colorBeforeScreenOff = FloatArray(3)
    private var lastScreenOff = 0L
    private var lastVoiceCommand = 0L
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
        strip = LedStrip(this, handler, onReady = ::onStripLinked, shouldBeOn = ::stripShouldBeOn)
        voice = VoiceEngine(this) { text -> handler.post { onVoiceCommand(text) } }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sync", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.deleteNotificationChannel("resume_sync") // from older versions
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED) // these come from the system
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                if (SyncState.running) toggleMic() else stopSelf() // nothing to toggle if sync isn't running
                return START_NOT_STICKY
            }
            ACTION_APPLY_TIMER -> {
                if (SyncState.running) {
                    handler.post { sendTimer(announce = true) }
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RETARGET -> {
                val address = SavedStrip.address(this)
                val name = SavedStrip.name(this)
                if (SyncState.running && address != null) {
                    handler.post { strip.retarget(address, name) }
                } else if (!SyncState.running) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        if (intent?.getBooleanExtra(EXTRA_SCREENSHOTS, false) == true) {
            if (!SyncState.running) startScreenshotSync()
            return START_NOT_STICKY
        }

        // A fresh consent token either starts sync or resumes it after the phone was locked
        val resuming = SyncState.running
        if (resuming && !SyncState.capturePaused) return START_NOT_STICKY
        if (!resuming) {
            SyncState.powerOn = true
            SyncState.syncMode = true
            SyncState.rainbow = false
            SyncState.micOn = false
            SyncState.screenOff = false
        }
        SyncState.capturePaused = false
        // Android 14+ requires the service to be foreground before the projection is created
        if (!updateForeground()) {
            SyncState.log("Screen capture could not start.")
            if (resuming) pauseState() else stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { resultData(it) }
        val mp = try {
            data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            SyncState.log("Screen capture could not start.")
            if (resuming) pauseState() else stopSelf()
            return START_NOT_STICKY
        }

        projection = mp
        mp.registerCallback(projectionCallback, handler)
        SyncState.running = true
        SyncState.log(if (resuming) "Screen Sync resumed" else "Screen Sync started")
        handler.post {
            startCapture(mp)
            if (!resuming) {
                strip.start()
                handler.post(tick)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        unregisterReceiver(screenReceiver)
        voice.release()
        SyncState.running = false
        SyncState.capturePaused = false
        SyncState.screenshotMode = false
        SyncState.micOn = false
        SyncState.color = Color.BLACK
        SyncState.log("Screen Sync stopped")
        handler.post {
            handler.removeCallbacks(tick)
            handler.removeCallbacks(screenshotLoop)
            virtualDisplay?.release()
            imageReader?.close()
            projection?.stop()
            val finish = {
                SyncState.screenOff = false
                strip.shutdown { thread.quitSafely() }
            }
            if (strip.isReady && stripPoweredOn && !SyncState.screenOff) {
                playOutro()
                handler.postDelayed(finish, introFrames().size * ANIMATION_STEP_MS)
            } else {
                finish()
            }
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------
    // SCREEN CAPTURE
    // -------------------------------------------------------------
    private fun startCapture(mp: MediaProjection) {
        val metrics = realMetrics()
        densityDpi = metrics.densityDpi
        val reader = newReader(metrics.widthPixels, metrics.heightPixels)
        virtualDisplay = mp.createVirtualDisplay(
            "PhotonSync", captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    /** Keeps the capture's aspect ratio matched to the screen so rotation doesn't letterbox in black bars. */
    private fun resizeCapture(screenWidth: Int, screenHeight: Int) {
        val vd = virtualDisplay ?: return
        val (w, h) = captureSize(screenWidth, screenHeight)
        if (w == captureWidth && h == captureHeight) return
        val old = imageReader
        val reader = newReader(screenWidth, screenHeight)
        // Android 14+ only allows one createVirtualDisplay per projection, so resize the existing one
        vd.resize(captureWidth, captureHeight, densityDpi)
        vd.surface = reader.surface
        old?.close()
    }

    private fun newReader(screenWidth: Int, screenHeight: Int): ImageReader {
        val (w, h) = captureSize(screenWidth, screenHeight)
        captureWidth = w
        captureHeight = h
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener(::onImageAvailable, handler)
        imageReader = reader
        return reader
    }

    private fun captureSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val scale = CAPTURE_LONG_SIDE.toFloat() / maxOf(screenWidth, screenHeight, 1)
        val even = { px: Int -> (px * scale).roundToInt().coerceAtLeast(2) and 1.inv() }
        return even(screenWidth) to even(screenHeight)
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android 14+ reports size changes through onCapturedContentResize instead
        if (Build.VERSION.SDK_INT < 34) {
            handler.post {
                val metrics = realMetrics()
                resizeCapture(metrics.widthPixels, metrics.heightPixels)
            }
        }
    }

    // -------------------------------------------------------------
    // PIXEL AVERAGING ENGINE
    // -------------------------------------------------------------
    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in 0 until image.height) {
                var i = y * rowStride
                repeat(image.width) {
                    r += buffer.get(i).toInt() and 0xff
                    g += buffer.get(i + 1).toInt() and 0xff
                    b += buffer.get(i + 2).toInt() and 0xff
                    i += pixelStride
                }
            }
            val n = (image.width * image.height).toFloat()
            target[0] = r / n
            target[1] = g / n
            target[2] = b / n
        } finally {
            image.close()
        }
    }

    /** Runs every 20ms: eases toward the screen colour (or holds the voice colour) and sends it when it changes. */
    private val tick = object : Runnable {
        override fun run() {
            val wantOn = stripShouldBeOn()
            if (wantOn && !stripPoweredOn && strip.isReady) {
                strip.setPower(true)
                stripPoweredOn = true
                lastSent.fill(-1)
            }
            if (!animating) {
                if (!wantOn && stripPoweredOn && strip.isReady) {
                    strip.setPower(false) // after any off animation has played
                    stripPoweredOn = false
                }
                for (c in 0..2) current[c] += SMOOTHING * (target[c] - current[c])
                val brightness = if (wantOn) SyncState.brightness else 0f
                val source = when {
                    SyncState.rainbow -> rainbowColor()
                    SyncState.syncMode -> current
                    else -> FloatArray(3) { staticColor[it].toFloat() }
                }
                val out = IntArray(3) { (source[it] * brightness).toInt().coerceIn(0, 255) }

                if (wantOn && strip.isReady && !out.contentEquals(lastSent)) {
                    strip.sendColor(out[0], out[1], out[2])
                    out.copyInto(lastSent)
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastUiUpdate >= UI_INTERVAL_MS) {
                    lastUiUpdate = now
                    SyncState.color = Color.rgb(out[0], out[1], out[2])
                    SyncState.notifyChanged()
                }
            }
            strip.flush()
            handler.postDelayed(this, TX_INTERVAL_MS)
        }
    }

    private fun stripShouldBeOn() = SyncState.powerOn && !SyncState.screenOff

    private fun onStripLinked() {
        stripPoweredOn = stripShouldBeOn()
        SyncState.log(if (stripPoweredOn) "Sent power-on to strip" else "Linked, staying off while the screen is off")
        playIntro(startDelay = 0L)
        handler.postDelayed({ sendTimer(announce = false) }, introFrames().size * ANIMATION_STEP_MS + 100)
    }

    private fun sendTimer(announce: Boolean) {
        val timer = StripTimer.load(this)
        val sent = strip.applyTimer(timer)
        if (announce || (sent && (timer.onEnabled || timer.offEnabled))) {
            SyncState.log(if (sent) "Timer sent to strip: ${timer.summary}" else "Timer saved. It goes to the strip once it connects.")
        }
    }

    // -------------------------------------------------------------
    // ANIMATION SEQUENCES
    // -------------------------------------------------------------
    private fun introFrames(): List<IntArray> = glide(INTRO_COLORS) + listOf(INTRO_COLORS.last())

    /** The intro's colours as a slow, endless loop. */
    private fun rainbowColor(): FloatArray {
        val frame = rainbowFrames[((SystemClock.uptimeMillis() / RAINBOW_STEP_MS) % rainbowFrames.size).toInt()]
        return FloatArray(3) { frame[it].toFloat() }
    }

    private fun glide(colors: List<IntArray>, stepsPerColor: Int = INTRO_STEPS_PER_COLOR): List<IntArray> = colors.zipWithNext().flatMap { (from, to) ->
        List(stepsPerColor) { step ->
            val mix = step.toFloat() / stepsPerColor
            IntArray(3) { (from[it] + mix * (to[it] - from[it])).roundToInt() }
        }
    }

    private fun playIntro(startDelay: Long) = playFrames(introFrames(), startDelay)

    /** The intro backwards. The tick switches the strip off once it finishes, leaving a colour for a timer to show. */
    private fun playOutro() = playFrames(introFrames().reversed(), startDelay = 0L)

    private fun playFrames(frames: List<IntArray>, startDelay: Long) {
        val id = ++animationId // a newer animation takes over from one still playing
        if (SyncState.screenOff) { // never light up a strip that is meant to be dark
            animating = false
            lastSent.fill(-1)
            return
        }
        animating = true
        frames.forEachIndexed { i, c ->
            handler.postDelayed({ if (id == animationId) strip.sendColor(c[0], c[1], c[2]) }, startDelay + i * ANIMATION_STEP_MS)
        }
        handler.postDelayed({
            if (id == animationId) {
                animating = false
                lastSent.fill(-1)
            }
        }, startDelay + frames.size * ANIMATION_STEP_MS)
    }

    // -------------------------------------------------------------
    // VOICE ENGINE (same command logic as the desktop app)
    // -------------------------------------------------------------
    private fun toggleMic() {
        if (SyncState.micOn) {
            SyncState.micOn = false
            voice.stop()
            updateForeground()
            SyncState.log("Voice Commands Muted")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            SyncState.log("Microphone permission needed. Tap the mic button in the app.")
            return
        }
        SyncState.micOn = true
        if (!updateForeground()) {
            SyncState.micOn = false
            SyncState.log("Android blocked the mic from the background. Open PhotonSync and tap the mic there.")
            return
        }
        voice.start()
        SyncState.log("Voice Commands Resumed")
    }

    private fun onVoiceCommand(text: String) {
        SyncState.log("[Voice Command]: \"$text\"")
        val now = SystemClock.uptimeMillis()
        if (now - lastVoiceCommand < VOICE_COOLDOWN_MS) return
        val words = text.split(" ")

        when {
            "on" in words || "turn on" in text || "power on" in text -> {
                if (!SyncState.powerOn) {
                    lastVoiceCommand = now
                    SyncState.powerOn = true
                    SyncState.syncMode = true
                    SyncState.rainbow = false
                    SyncState.log("Power ON (Screen Sync Active)")
                    playIntro(startDelay = if (stripPoweredOn) 0L else WAKE_COMMANDS_MS)
                }
            }
            "off" in words || "turn off" in text || "power off" in text -> {
                if (SyncState.powerOn) {
                    lastVoiceCommand = now
                    SyncState.powerOn = false
                    SyncState.log("Power OFF")
                    playOutro()
                }
            }
            "normal" in words || "sync" in words || "screen sync" in text -> {
                lastVoiceCommand = now
                SyncState.syncMode = true
                SyncState.rainbow = false
                SyncState.powerOn = true
                SyncState.log("Resumed Screen Sync Mode")
            }
            "rainbow" in words -> {
                lastVoiceCommand = now
                SyncState.syncMode = false
                SyncState.rainbow = true
                SyncState.powerOn = true
                SyncState.log("Rainbow mode")
            }
            "low" in words -> setBrightness(now, 30, "LOW")
            "medium" in words -> setBrightness(now, 60, "MEDIUM")
            "high" in words -> setBrightness(now, 100, "HIGH")
            else -> {
                val match = VoiceEngine.COLOR_DICTIONARY.keys.sortedByDescending { it.length }.firstOrNull { it in text }
                if (match != null && SyncState.powerOn) {
                    lastVoiceCommand = now
                    SyncState.syncMode = false
                    SyncState.rainbow = false
                    VoiceEngine.COLOR_DICTIONARY.getValue(match).copyInto(staticColor)
                    SyncState.log("Color Changed -> ${match.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }}")
                }
            }
        }
        SyncState.notifyChanged()
    }

    private fun setBrightness(now: Long, percent: Int, label: String) {
        lastVoiceCommand = now
        SyncState.brightness = percent / 100f
        SyncState.log("Brightness -> $label ($percent%)")
    }

    // -------------------------------------------------------------
    // SCREEN OFF / ON / UNLOCK
    // Screen off: strip goes dark. Screen on: strip comes back at once with the last colour.
    // Android 15+ also ends the screen share on every lock, so live sync restarts after the unlock.
    // -------------------------------------------------------------
    // Unplugged, the CPU can sleep right after the screen goes off and strand the power-off commands, leaving the strip lit
    private val powerOffWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotonSync:powerOff")
            .apply { setReferenceCounted(false) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    powerOffWakeLock.acquire(POWER_OFF_AWAKE_MS)
                    handler.post(::onScreenOff)
                }
                Intent.ACTION_SCREEN_ON -> handler.post(::onScreenOn)
                Intent.ACTION_USER_PRESENT -> handler.post(::resumeCaptureIfPaused)
            }
        }
    }

    private fun onScreenOff() {
        current.copyInto(colorBeforeScreenOff)
        lastScreenOff = SystemClock.uptimeMillis()
        SyncState.screenOff = true
        // Send it now, rather than waiting for the next tick
        if (!animating && strip.isReady && stripPoweredOn) {
            strip.setPower(false)
            stripPoweredOn = false
        }
        SyncState.notifyChanged()
    }

    private fun onScreenOn() {
        SyncState.screenOff = false
        // The last frames before sleep are often black, which would leave the strip dark on wake
        val restored = if (colorBeforeScreenOff.max() < TOO_DARK) FloatArray(3) { WAKE_FALLBACK_LEVEL } else colorBeforeScreenOff
        restored.copyInto(target)
        restored.copyInto(current)
        SyncState.notifyChanged()
        if (!isLocked()) resumeCaptureIfPaused() // no keyguard, so there won't be an unlock to wait for
    }

    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    private fun resumeCaptureIfPaused() {
        if (!SyncState.running || !SyncState.capturePaused) return
        if (ScreenColorService.isEnabled) {
            // Turned on since this session began: no need to ask for another screen share
            SyncState.capturePaused = false
            SyncState.screenshotMode = true
            updateForeground()
            handler.removeCallbacks(screenshotLoop)
            handler.post(screenshotLoop)
            SyncState.log("Screen Sync resumed")
            SyncState.notifyChanged()
        }
    }

    // -------------------------------------------------------------
    // NO-PROMPT CAPTURE (accessibility screenshots)
    // -------------------------------------------------------------
    private fun startScreenshotSync() {
        SyncState.powerOn = true
        SyncState.syncMode = true
        SyncState.rainbow = false
        SyncState.micOn = false
        SyncState.screenOff = false
        SyncState.capturePaused = false
        SyncState.screenshotMode = true
        if (!updateForeground()) {
            SyncState.screenshotMode = false
            SyncState.log("Screen sync could not start.")
            stopSelf()
            return
        }
        SyncState.running = true
        SyncState.log("Screen Sync started (no-tap mode)")
        handler.post {
            strip.start()
            handler.post(tick)
            handler.post(screenshotLoop)
        }
    }

    private var screenshotWarningShown = false

    private val screenshotLoop = object : Runnable {
        override fun run() {
            when {
                SyncState.screenOff -> {} // nothing to read, and it would only return black
                ScreenColorService.isEnabled -> {
                    screenshotWarningShown = false
                    ScreenColorService.captureAverage(handler) { r, g, b ->
                        if (!SyncState.screenOff) {
                            target[0] = r
                            target[1] = g
                            target[2] = b
                        }
                    }
                }
                !screenshotWarningShown -> {
                    screenshotWarningShown = true
                    SyncState.log("No-tap mode was turned off in Accessibility settings, so colours stopped updating.")
                }
            }
            handler.postDelayed(this, SCREENSHOT_INTERVAL_MS)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopping) return
            val screenJustSlept = SystemClock.uptimeMillis() - lastScreenOff < SCREEN_OFF_GRACE_MS
            if (isLocked() || SyncState.screenOff || screenJustSlept) {
                pauseCapture()
            } else {
                SyncState.log("Screen sharing was stopped.")
                stopSelf()
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            resizeCapture(width, height)
        }
    }

    /** Keeps the strip link, voice and service alive while the screen share is gone. */
    private fun pauseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection = null
        SyncState.log("Phone locked: open PhotonSync after unlocking to restart live sync.")
        pauseState()
        if (!isLocked()) resumeCaptureIfPaused() // the share ended after the phone had already woken up
    }

    private fun pauseState() {
        SyncState.capturePaused = true
        SyncState.notifyChanged()
        updateForeground()
    }

    // -------------------------------------------------------------
    // NOTIFICATION
    // -------------------------------------------------------------
    /**
     * (Re)posts the foreground notification. Screen capture is only claimed while a share is live (otherwise
     * it's just the BLE link), and the microphone only while the mic is on.
     */
    private fun updateForeground(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                var types = if (SyncState.capturePaused || SyncState.screenshotMode) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (SyncState.micOn) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, buildNotification(), types)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenSyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        // Without the mic permission yet, the button opens the app so it can ask for it
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val mic = if (micGranted) {
            PendingIntent.getService(
                this, 2, Intent(this, ScreenSyncService::class.java).setAction(ACTION_TOGGLE_MIC), PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getActivity(
                this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ENABLE_MIC, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val micLabel = if (SyncState.micOn) "Mic off" else "Mic on"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PhotonSync")
            .setContentText(
                (if (SyncState.capturePaused) "Paused while locked" else "Screen sync on") +
                    (if (SyncState.micOn) " · listening" else " · mic off")
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), micLabel, mic).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), "Stop", stop).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun resultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 34) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
}
