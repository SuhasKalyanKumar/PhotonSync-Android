package me.suhaskalyan.photonsync

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView

/**
 * The daily on/off alarm. It lives on the strip's own clock, so once sent it fires with the phone off or out of range.
 * The app keeps a copy to re-send (with the current time) every time it connects.
 */
data class StripTimer(
    val onEnabled: Boolean,
    val onHour: Int,
    val onMinute: Int,
    val offEnabled: Boolean,
    val offHour: Int,
    val offMinute: Int,
) {
    val summary: String
        get() = when {
            onEnabled && offEnabled -> "On ${time(onHour, onMinute)} · Off ${time(offHour, offMinute)}"
            onEnabled -> "On ${time(onHour, onMinute)}"
            offEnabled -> "Off ${time(offHour, offMinute)}"
            else -> "Not set"
        }

    fun save(context: Context) {
        prefs(context).edit()
            .putBoolean("timer_on_enabled", onEnabled).putInt("timer_on_hour", onHour).putInt("timer_on_minute", onMinute)
            .putBoolean("timer_off_enabled", offEnabled).putInt("timer_off_hour", offHour).putInt("timer_off_minute", offMinute)
            .apply()
    }

    companion object {
        fun time(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

        fun load(context: Context): StripTimer {
            val p = prefs(context)
            return StripTimer(
                p.getBoolean("timer_on_enabled", false), p.getInt("timer_on_hour", 7), p.getInt("timer_on_minute", 0),
                p.getBoolean("timer_off_enabled", false), p.getInt("timer_off_hour", 23), p.getInt("timer_off_minute", 0),
            )
        }

        private fun prefs(context: Context) = context.getSharedPreferences("photonsync", Context.MODE_PRIVATE)
    }
}

/** The timer menu: an on time and an off time, each with its own switch. [onSaved] gets the new timer on Save. */
@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
fun showTimerDialog(activity: Activity, onSaved: (StripTimer) -> Unit) = TimerMenu(activity, onSaved).show()

@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
private class TimerMenu(activity: Activity, private val onSaved: (StripTimer) -> Unit) : GlassUi(activity) {
    private val accent = activity.getColor(R.color.mauve)
    private val dim = activity.getColor(R.color.subtext)

    private inner class Line(title: String, enabled: Boolean, var hour: Int, var minute: Int) {
        val toggle = Switch(activity).apply {
            isChecked = enabled
            val checked = intArrayOf(android.R.attr.state_checked)
            thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(accent, dim))
            trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(Color.argb(110, 203, 166, 247), Color.argb(60, 255, 255, 255)))
        }
        private val clock = TextView(activity).apply {
            textSize = 32f
            typeface = displayFace
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(14))
            isClickable = true
            setOnClickListener {
                showTimePicker(title, hour, minute) { h, m ->
                    hour = h
                    minute = m
                    toggle.isChecked = true // picking a time means you want it on
                    refresh()
                }
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(11f, activity.getColor(R.color.text)).apply { text = title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle)
            })
            addView(clock)
        }

        val enabled get() = toggle.isChecked

        fun refresh() {
            clock.text = StripTimer.time(hour, minute)
            clock.setTextColor(if (enabled) activity.getColor(R.color.text) else dim)
            card.background = rounded(glassFill, 18, if (enabled) accent else glassEdge)
        }

        init {
            toggle.setOnCheckedChangeListener { _, _ -> refresh() }
            refresh()
        }
    }

    fun show() {
        val saved = StripTimer.load(activity)
        val on = Line("TURN ON", saved.onEnabled, saved.onHour, saved.onMinute)
        val off = Line("TURN OFF", saved.offEnabled, saved.offHour, saved.offMinute)
        val popup = Dialog(activity)
        val cardSpacing = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            addView(heading("STRIP TIMER"))
            addView(text(13f, activity.getColor(R.color.text)).apply {
                text = "Every day, on the strip's own clock. Your phone can be off."
                setPadding(0, dp(6), 0, dp(16))
            })
            addView(on.card, cardSpacing)
            addView(off.card, LinearLayout.LayoutParams(cardSpacing))
            addView(buttons(
                pillButton("CANCEL") { popup.dismiss() },
                pillButton("SAVE", primary = true) {
                    val timer = StripTimer(on.enabled, on.hour, on.minute, off.enabled, off.hour, off.minute)
                    timer.save(activity)
                    onSaved(timer)
                    popup.dismiss()
                },
            ))
        }
        showSheet(popup, content)
    }

    /** Scrolling hour and minute wheels, in the menu's own look instead of Android's clock face. */
    private fun showTimePicker(title: String, hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
        val popup = Dialog(activity)
        val hours = wheel(23, hour)
        val minutes = wheel(59, minute)
        val wheels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(hours)
            addView(TextView(activity).apply {
                text = ":"
                textSize = 26f
                typeface = displayFace
                setTextColor(activity.getColor(R.color.text))
                setPadding(dp(18), 0, dp(18), 0)
            })
            addView(minutes)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            addView(heading(title))
            addView(wheels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(buttons(
                pillButton("CANCEL") { popup.dismiss() },
                pillButton("SET", primary = true) {
                    hours.clearFocus()
                    minutes.clearFocus()
                    onPicked(hours.value, minutes.value)
                    popup.dismiss()
                },
            ))
        }
        showSheet(popup, content)
    }

    private fun wheel(max: Int, current: Int) = NumberPicker(activity).apply {
        minValue = 0
        maxValue = max
        value = current
        setFormatter { "%02d".format(it) }
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) {
            setTextColor(activity.getColor(R.color.text))
            setSelectionDividerHeight(dp(1))
        }
    }

    private fun buttons(cancel: View, confirm: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(12), 0, 0)
        addView(cancel)
        addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    }

    private fun showSheet(popup: Dialog, content: View) {
        popup.setContentView(glassSheet(content))
        popup.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.show()
    }
}
