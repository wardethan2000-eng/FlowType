package com.ethanward.flowtype.asr

import com.ethanward.flowtype.audio.Wav
import java.io.File

/**
 * Transcribes while you talk (PLAN §4.3 live chunking). Mic frames go through
 * Silero VAD; each time it closes a stretch of speech, that stretch is decoded
 * straight away, padded with 0.5 s before and 0.25 s after. At the end only
 * the unfinished tail is left to decode.
 *
 * Use from one thread (the service's asr thread), in order: [accept] for each
 * frame, then [finish] once, then [release].
 */
class LiveChunker(vadModel: File, private val decode: (FloatArray) -> String) {
    private val vad = Segmenter.newVad(vadModel)
    private var audio = FloatArray(Wav.RATE * 30)
    private var size = 0
    private var fed = 0
    private val pieces = ArrayList<Piece>()
    /** Time spent decoding pieces while recording. */
    var decodedWhileRecordingMs = 0L
        private set

    data class Result(val pieces: List<Piece>, val tailMs: Long, val wholeFallback: Boolean)

    fun accept(frame: ShortArray) {
        if (size + frame.size > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, size + frame.size))
        for (s in frame) audio[size++] = s / 32768f
        while (size - fed >= Segmenter.WINDOW) {
            vad.acceptWaveform(audio.copyOfRange(fed, fed + Segmenter.WINDOW))
            fed += Segmenter.WINDOW
            val started = System.nanoTime()
            if (drain()) decodedWhileRecordingMs += (System.nanoTime() - started) / 1_000_000
        }
    }

    /** Decodes what's left. With no speech found at all, decodes everything, just in case. */
    fun finish(): Result {
        val started = System.nanoTime()
        if (size > fed) {
            val last = FloatArray(Segmenter.WINDOW)
            audio.copyInto(last, 0, fed, size)
            vad.acceptWaveform(last)
        }
        vad.flush()
        drain()
        var whole = false
        if (pieces.isEmpty() && size > 0) {
            pieces += Piece(Span(0, size), decode(audio.copyOf(size)))
            whole = true
        }
        return Result(pieces.toList(), (System.nanoTime() - started) / 1_000_000, whole)
    }

    fun release() = vad.release()

    /** Decodes every segment the VAD has closed. True if there were any. */
    private fun drain(): Boolean {
        var any = false
        while (!vad.empty()) {
            val seg = vad.front()
            val span = Span(seg.start, minOf(size, seg.start + seg.samples.size))
            vad.pop()
            val padded = span.padded(PAD_BEFORE, PAD_AFTER, size)
            pieces += Piece(span, decode(audio.copyOfRange(padded.start, padded.end)))
            any = true
        }
        return any
    }

    companion object {
        const val PAD_BEFORE = Wav.RATE / 2
        const val PAD_AFTER = Wav.RATE / 4
    }
}
