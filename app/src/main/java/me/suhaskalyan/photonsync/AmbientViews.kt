package me.suhaskalyan.photonsync

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val FRAME_MS = 33L
private val IDLE_ACCENT = Color.rgb(203, 166, 247)
private val SPECTRUM = intArrayOf(
    Color.rgb(255, 60, 80), Color.rgb(255, 150, 40), Color.rgb(255, 225, 70), Color.rgb(60, 240, 140),
    Color.rgb(70, 130, 255), Color.rgb(120, 70, 220), Color.rgb(238, 130, 238), Color.rgb(255, 60, 80),
)

/** Strip colours can be nearly black, which wouldn't glow. Keeps the hue and lifts it to something visible. */
private fun visibleAccent(color: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    if (hsv[2] < 0.12f) return IDLE_ACCENT
    hsv[2] = hsv[2].coerceAtLeast(0.75f)
    return Color.HSVToColor(hsv)
}

private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** Slowly drifting colour clouds behind the whole screen. One follows the strip's live colour. */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Cloud(val paint: Paint, val speedX: Float, val speedY: Float, val phase: Float, val size: Float)

    private val matrixForCloud = Matrix()
    private var clouds = emptyList<Cloud>()
    private var accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var accent = IDLE_ACCENT
    private var cloudRadius = 1f

    fun setAccent(color: Int) {
        val next = visibleAccent(color)
        if (next == accent) return
        accent = next
        accentPaint.shader = gradient(accent, 90)
    }

    /** Fills whatever it's given but never asks for space, so a menu is only as tall as its content. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    private fun gradient(color: Int, alpha: Int) = RadialGradient(
        0f, 0f, cloudRadius, withAlpha(color, alpha), Color.TRANSPARENT, Shader.TileMode.CLAMP,
    )

    private fun screenPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.SCREEN else xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cloudRadius = maxOf(w, h) * 0.55f
        accentPaint = screenPaint().apply { shader = gradient(accent, 90) }
        clouds = listOf(
            Cloud(screenPaint().apply { shader = gradient(Color.rgb(120, 70, 220), 80) }, 0.00011f, 0.00017f, 0f, 1f),
            Cloud(screenPaint().apply { shader = gradient(Color.rgb(30, 160, 200), 60) }, 0.00016f, 0.00009f, 2.1f, 0.85f),
            Cloud(accentPaint, 0.00008f, 0.00013f, 4.2f, 0.9f),
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(context.getColor(R.color.base))
        val t = SystemClock.uptimeMillis()
        clouds.forEach { cloud ->
            val x = width * (0.5f + 0.45f * sin(t * cloud.speedX + cloud.phase))
            val y = height * (0.5f + 0.45f * cos(t * cloud.speedY + cloud.phase))
            matrixForCloud.setScale(cloud.size, cloud.size)
            matrixForCloud.postTranslate(x, y)
            cloud.paint.shader?.setLocalMatrix(matrixForCloud)
            canvas.drawCircle(x, y, cloudRadius * cloud.size, cloud.paint)
        }
        postInvalidateDelayed(FRAME_MS)
    }
}

/** The centrepiece: a breathing orb in the strip's live colour, inside a slowly turning ring of all seven colours. */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * density
        alpha = 110
        maskFilter = BlurMaskFilter(9 * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spin = Matrix()
    private val glowScale = Matrix()
    private var shown = FloatArray(3) { Color.red(IDLE_ACCENT) / 255f }
    private var target = IDLE_ACCENT
    private var radius = 1f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // BlurMaskFilter needs it, and the view is small
    }

    fun setAccent(color: Int) {
        target = visibleAccent(color)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius = min(w, h) * 0.5f
        val sweep = SweepGradient(w / 2f, h / 2f, SPECTRUM, null)
        ringPaint.shader = sweep
        ringGlowPaint.shader = sweep
    }

    private fun current(): Int {
        // Glide to the new colour, so the orb never snaps even when the screen does
        val goal = floatArrayOf(Color.red(target) / 255f, Color.green(target) / 255f, Color.blue(target) / 255f)
        for (i in 0..2) shown[i] += 0.12f * (goal[i] - shown[i])
        return Color.rgb((shown[0] * 255).toInt(), (shown[1] * 255).toInt(), (shown[2] * 255).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val cx = width / 2f
        val cy = height / 2f
        val breath = 0.5f + 0.5f * sin(t * 0.0016f)
        val color = current()

        val glowRadius = radius * (0.72f + 0.1f * breath)
        glowPaint.shader = RadialGradient(cx, cy, glowRadius, withAlpha(color, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        val coreRadius = radius * (0.34f + 0.025f * breath)
        corePaint.shader = RadialGradient(cx, cy, coreRadius, Color.WHITE, color, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        spin.setRotate(t * 0.02f % 360f, cx, cy)
        ringPaint.shader?.setLocalMatrix(spin)
        val ringRadius = radius * 0.8f
        canvas.drawCircle(cx, cy, ringRadius, ringGlowPaint)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        glowScale.reset()
        postInvalidateDelayed(FRAME_MS)
    }
}
