package me.suhaskalyan.photonsync

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.annotation.TargetApi
import kotlin.math.roundToInt

/**
 * Optional capture source for no-prompt sync. Screenshots via accessibility survive locking and need no
 * "Start sharing?" consent, but Android limits them to about 3 a second. Each one is shrunk straight down to its
 * average colour, and is never stored or sent anywhere. This service reads no window content or accessibility events.
 */
class ScreenColorService : AccessibilityService() {

    companion object {
        private const val SAMPLE_LONG_SIDE = 64

        @Volatile private var instance: ScreenColorService? = null

        val isEnabled: Boolean get() = Build.VERSION.SDK_INT >= 30 && instance != null

        /** Calls [onColor] on [handler]'s thread with the screen's average r, g, b. Skipped if the shot fails. */
        fun captureAverage(handler: Handler, onColor: (Float, Float, Float) -> Unit) {
            if (Build.VERSION.SDK_INT >= 30) instance?.capture(handler, onColor)
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    @TargetApi(30)
    private fun capture(handler: Handler, onColor: (Float, Float, Float) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, { handler.post(it) }, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
                    val small = shrink(shot)
                    shot.recycle()
                    val pixels = IntArray(small.width * small.height)
                    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                    small.recycle()
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    for (p in pixels) {
                        r += (p shr 16) and 0xff
                        g += (p shr 8) and 0xff
                        b += p and 0xff
                    }
                    val n = pixels.size.toFloat()
                    onColor(r / n, g / n, b / n)
                } catch (e: RuntimeException) {
                    SyncState.log("Screen read failed: ${e.message}")
                } finally {
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {} // too soon or a protected window: keep the last colour
        })
    }

    /** Lets the GPU do the downscale, so only a tiny bitmap is ever copied into app memory. */
    @SuppressLint("NewApi")
    private fun shrink(shot: Bitmap): Bitmap {
        val scale = SAMPLE_LONG_SIDE.toFloat() / maxOf(shot.width, shot.height)
        val w = (shot.width * scale).roundToInt().coerceAtLeast(1)
        val h = (shot.height * scale).roundToInt().coerceAtLeast(1)
        val picture = Picture()
        val canvas: Canvas = picture.beginRecording(w, h)
        canvas.drawBitmap(shot, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        picture.endRecording()
        val hardware = Bitmap.createBitmap(picture, w, h, Bitmap.Config.ARGB_8888)
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        return software
    }
}
