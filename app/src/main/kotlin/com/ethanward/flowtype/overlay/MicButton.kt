package com.ethanward.flowtype.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.ethanward.flowtype.R

/**
 * The overlay. Idle: a dark mic circle, see-through so it hides less of the app.
 * Listening: a panel with ✕ (throw it away), a live waveform, and ✓ (type it).
 * Busy: the circle with a spinner while the phone transcribes.
 */
class MicButton(context: Context) : FrameLayout(context) {
    enum class State { IDLE, RECORDING, BUSY }

    var onCancel: (() -> Unit)? = null
    var onAccept: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val icon = ImageView(context)
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
    }
    /** The round button: idle and busy. Drag and tap land here. */
    val face = FrameLayout(context)
    private val panel = LinearLayout(context)
    private val waveform = WaveformView(context)
    private var state = State.IDLE
    private var dragging = false

    init {
        val size = dp(SIZE_DP)
        face.background = circle
        face.elevation = dp(6).toFloat()
        face.contentDescription = "Dictate"
        icon.setImageResource(R.drawable.ic_mic)
        face.addView(icon, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        face.addView(spinner, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        addView(face, LayoutParams(size, size, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(8) })

        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.background = GradientDrawable().apply {
            cornerRadius = dp(PANEL_HEIGHT_DP / 2).toFloat()
            setColor(context.getColor(R.color.panel_background))
        }
        panel.elevation = dp(6).toFloat()
        panel.setPadding(dp(6), 0, dp(6), 0)
        panel.addView(roundAction(R.drawable.ic_close, R.color.panel_cancel, "Discard") { onCancel?.invoke() })
        panel.addView(waveform, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        panel.addView(roundAction(R.drawable.ic_check, R.color.panel_accept, "Type it") { onAccept?.invoke() })
        addView(panel, LayoutParams(dp(PANEL_WIDTH_DP), dp(PANEL_HEIGHT_DP), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = dp(8)
        })

        clipChildren = false
        clipToPadding = false
        setState(State.IDLE)
    }

    private fun roundAction(iconRes: Int, colorRes: Int, label: String, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(colorRes))
            }
            contentDescription = label
            addView(ImageView(context).apply { setImageResource(iconRes) }, LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(ACTION_DP), dp(ACTION_DP))
        }

    fun setState(state: State) {
        this.state = state
        val recording = state == State.RECORDING
        panel.visibility = if (recording) VISIBLE else GONE
        face.visibility = if (recording) GONE else VISIBLE
        if (recording) waveform.clear()
        circle.setColor(context.getColor(if (state == State.BUSY) R.color.button_busy else R.color.button_idle))
        icon.visibility = if (state == State.BUSY) INVISIBLE else VISIBLE
        spinner.visibility = if (state == State.BUSY) VISIBLE else GONE
        face.contentDescription = if (state == State.BUSY) "Transcribing" else "Dictate"
        updateAlpha()
    }

    /** Solid while it's being moved, so you can see where it goes. */
    fun setDragging(on: Boolean) {
        dragging = on
        updateAlpha()
    }

    private fun updateAlpha() {
        face.alpha = if (state == State.IDLE && !dragging) IDLE_ALPHA else 1f
    }

    /** Loudness of the last 30 ms (RMS, 0..1). */
    fun setLevel(rms: Float) {
        if (state == State.RECORDING) waveform.push(rms)
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        const val SIZE_DP = 52
        const val ACTION_DP = 40
        const val PANEL_WIDTH_DP = 232
        const val PANEL_HEIGHT_DP = 52
        /** The window around the circle leaves room for its shadow. */
        const val WINDOW_DP = 68
        /** The window while listening: the panel plus the same margins. */
        const val PANEL_WINDOW_DP = PANEL_WIDTH_DP + 16
        const val IDLE_ALPHA = 0.5f
    }
}
