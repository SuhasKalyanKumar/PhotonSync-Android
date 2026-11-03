package me.suhaskalyan.photonsync

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/** State shared between the dashboard and the sync service (both live in the same process). */
object SyncState {
    private const val MAX_LOG_LINES = 200

    @Volatile var running = false
    @Volatile var brightness = 1f
    @Volatile var powerOn = true
    @Volatile var syncMode = true // true = screen sync, false = static voice colour
    @Volatile var rainbow = false // looping the intro's glide, by voice command
    @Volatile var micOn = false
    @Volatile var capturePaused = false // screen share ended by a lock; waiting for consent after unlock
    @Volatile var screenshotMode = false // colours come from ScreenColorService instead of a screen share
    @Volatile var screenOff = false // the strip stays dark until the screen comes back on
    @Volatile var stripName: String? = null
    @Volatile var color = Color.BLACK
    @Volatile var logVersion = 0
        private set

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Appends a line to the telemetry log (and logcat), same as log_msg on desktop. */
    fun log(msg: String) {
        Log.i("PhotonSync", msg)
        synchronized(lines) {
            lines.addLast("> $msg")
            while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            logVersion++
        }
        notifyChanged()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun notifyChanged() {
        mainHandler.post { listeners.forEach { it() } }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)
}
