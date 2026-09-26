package com.ethanward.flowtype.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** A stretch of speech in a recording, in samples. */
data class Span(val start: Int, val end: Int) {
    val length: Int get() = end - start

    /**
     * Widened by [before] and [after] samples, clamped to the recording. The
     * padding before recovers words that tight VAD cuts decode to nothing
     * (PLAN §4.3).
     */
    fun padded(before: Int, after: Int, total: Int) =
        Span(maxOf(0, start - before), minOf(total, end + after))
}

/**
 * Splits a whole recording at pauses with Silero VAD, the way live chunking
 * will while recording (PLAN §4.3: pause > 0.5 s, forced cut at 20 s).
 */
class Segmenter(vadModel: File) {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModel.path,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = 20f,
            ),
            sampleRate = 16_000,
            numThreads = 1,
        ),
    )

    fun split(samples: FloatArray): List<Span> {
        vad.reset()
        val spans = ArrayList<Span>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                spans += Span(seg.start, seg.start + seg.samples.size)
                vad.pop()
            }
        }
        var i = 0
        while (i + WINDOW <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + WINDOW))
            drain()
            i += WINDOW
        }
        vad.flush()
        drain()
        return spans
    }

    fun release() = vad.release()

    companion object {
        const val WINDOW = 512
    }
}

/**
 * Joins per-segment decodes. Phase 0 keeps this plain (a space between
 * segments) so the bench shows the raw size of the join problem.
 */
fun joinSegments(texts: List<String>): String =
    texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
