package me.suhaskalyan.photonsync

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The look shared by the app's menus: dark glass over the main screen's moving colours. */
open class GlassUi(protected val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    protected val displayFace: Typeface = activity.resources.getFont(R.font.michroma)
    protected val textFace: Typeface = activity.resources.getFont(R.font.photon_text)
    protected val glassFill = Color.argb(165, 10, 10, 18)
    protected val glassEdge = Color.argb(50, 255, 255, 255)

    protected fun dp(value: Int) = (value * density).roundToInt()

    protected fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        cornerRadius = radiusDp * density
        setColor(color)
        strokeColor?.let { setStroke(dp(1).coerceAtLeast(1), it) }
    }

    protected fun text(size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(textFace, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** A small uppercase label, the look of every action and status in the menus. */
    protected fun label(size: Float, color: Int) = text(size, color, bold = true).apply { letterSpacing = 0.14f }

    protected fun heading(value: String) = TextView(activity).apply {
        text = value
        textSize = 15f
        typeface = displayFace
        letterSpacing = 0.08f
        setTextColor(activity.getColor(R.color.text))
    }

    protected fun pillButton(value: String, primary: Boolean = false, onClick: () -> Unit) =
        label(11f, activity.getColor(if (primary) R.color.crust else R.color.text)).apply {
            text = value
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = if (primary) rounded(activity.getColor(R.color.mauve), 100) else rounded(Color.argb(26, 255, 255, 255), 100, glassEdge)
            setOnClickListener { onClick() }
        }

    /** The main screen's moving colours behind a rounded panel. */
    protected fun glassSheet(content: View) = FrameLayout(activity).apply {
        background = rounded(activity.getColor(R.color.base), 28)
        clipToOutline = true
        addView(AuroraView(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
