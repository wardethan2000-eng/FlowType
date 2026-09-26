package com.ethanward.flowtype.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.log10

/**
 * The "I can hear you" indicator: a row of bars, newest on the right, each the
 * loudness of one 30 ms mic frame. Only loudness reaches it, never audio.
 */
class WaveformView(context: Context) : View(context) {
    private val levels = FloatArray(BARS)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    fun push(rms: Float) {
        levels[head] = levelOf(rms)
        head = (head + 1) % BARS
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val slot = width.toFloat() / BARS
        val bar = slot * 0.55f
        val minH = bar
        val mid = height / 2f
        for (i in 0 until BARS) {
            val v = levels[(head + i) % BARS]
            val h = maxOf(minH, v * height * 0.9f)
            val left = i * slot + (slot - bar) / 2
            canvas.drawRoundRect(left, mid - h / 2, left + bar, mid + h / 2, bar / 2, bar / 2, paint)
        }
    }

    companion object {
        const val BARS = 28

        /** RMS (0..1) to bar height (0..1) on a dB scale: -55 dB is flat, -15 dB fills it. */
        fun levelOf(rms: Float): Float {
            if (rms <= 0f) return 0f
            val db = 20f * log10(rms)
            return ((db + 55f) / 40f).coerceIn(0f, 1f)
        }
    }
}
