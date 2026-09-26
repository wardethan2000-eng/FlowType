package com.ethanward.flowtype.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.ethanward.flowtype.R

/** The plain Phase 0 button: a dark circle with a mic; red while recording. */
class MicButton(context: Context) : FrameLayout(context) {
    enum class State { IDLE, RECORDING, BUSY }

    private val density = resources.displayMetrics.density
    private val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val icon = ImageView(context)
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
    }
    private val face = FrameLayout(context)

    init {
        val size = dp(SIZE_DP)
        face.background = circle
        face.elevation = dp(6).toFloat()
        face.addView(icon, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        face.addView(spinner, LayoutParams(dp(28), dp(28), Gravity.CENTER))
        addView(face, LayoutParams(size, size, Gravity.CENTER))
        clipChildren = false
        contentDescription = "Dictate"
        setState(State.IDLE)
    }

    fun setState(state: State) {
        val color = when (state) {
            State.IDLE -> R.color.button_idle
            State.RECORDING -> R.color.button_recording
            State.BUSY -> R.color.button_busy
        }
        circle.setColor(context.getColor(color))
        icon.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        icon.visibility = if (state == State.BUSY) INVISIBLE else VISIBLE
        spinner.visibility = if (state == State.BUSY) VISIBLE else GONE
        contentDescription = when (state) {
            State.IDLE -> "Dictate"
            State.RECORDING -> "Stop dictating"
            State.BUSY -> "Transcribing"
        }
        if (state != State.RECORDING) setLevel(0f)
    }

    /** Pulses with the voice: [rms] of the last 30 ms, 0..1. */
    fun setLevel(rms: Float) {
        val scale = 1f + minOf(0.18f, rms * 3f)
        face.scaleX = scale
        face.scaleY = scale
    }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        const val SIZE_DP = 52
        /** The window around the circle leaves room for the pulse and shadow. */
        const val WINDOW_DP = 68
    }
}
