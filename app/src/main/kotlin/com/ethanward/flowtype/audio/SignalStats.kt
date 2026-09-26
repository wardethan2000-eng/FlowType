package com.ethanward.flowtype.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Loudness numbers for a recording, safe to log (they say nothing about what
 * was said). Phase 0 uses them to check that the mic isn't handing the
 * background service silence (PLAN §4.2).
 */
data class SignalStats(val peak: Float, val rms: Float, val zeroFraction: Float) {
    /** All-zero or near-zero buffers: what a muted background mic returns. */
    val silent: Boolean get() = peak < 1e-4f

    companion object {
        fun of(samples: FloatArray): SignalStats {
            if (samples.isEmpty()) return SignalStats(0f, 0f, 1f)
            var peak = 0f
            var sumSq = 0.0
            var zeros = 0
            for (s in samples) {
                val a = abs(s)
                if (a > peak) peak = a
                sumSq += s * s
                if (s == 0f) zeros++
            }
            return SignalStats(peak, sqrt(sumSq / samples.size).toFloat(), zeros.toFloat() / samples.size)
        }

        fun rms(samples: ShortArray, count: Int = samples.size): Float {
            if (count == 0) return 0f
            var sumSq = 0.0
            for (i in 0 until count) {
                val s = samples[i] / 32768.0
                sumSq += s * s
            }
            return sqrt(sumSq / count).toFloat()
        }
    }
}
