package com.ethanward.flowtype.asr

import com.ethanward.flowtype.audio.Wav
import java.io.File
import kotlin.math.sqrt

/**
 * Transcribes while you talk (PLAN §4.3 live chunking). Mic frames go through
 * Silero VAD; each time it closes a stretch of speech, the recording is cut at
 * the quietest moment of the pause that closed it, and everything since the
 * last cut is decoded straight away. At the end only the tail after the last
 * cut is left to decode.
 *
 * The pieces cover the whole recording: the VAD only picks where to cut. It
 * used to pick what to decode too, and speech too quiet for it (a phone held
 * low, a trailing word) was never decoded at all.
 *
 * Use from one thread (the service's asr thread), in order: [accept] for each
 * frame, then [finish] once, then [release].
 */
class LiveChunker(vadModel: File, private val decode: (FloatArray) -> String) {
    private val vad = Segmenter.newVad(vadModel)
    private var audio = FloatArray(Wav.RATE * 30)
    private var size = 0
    private var fed = 0
    /** Where the next piece starts: the end of the last one. */
    private var cut = 0
    /** The speech in the tail, when the VAD found some: the join rules read pauses from it. */
    private var tailSpeech: Span? = null
    /** Samples handed to [accept]; compared with what the mic captured, to catch lost frames. */
    val samplesIn: Int get() = size
    private val pieces = ArrayList<Piece>()
    /** Time spent decoding pieces while recording. */
    var decodedWhileRecordingMs = 0L
        private set

    /**
     * [tailLoudness]: the last piece's speech RMS over the rest's, when there
     * are two or more; to tell handling noise from a word said on purpose.
     * [droppedFiller]: [dropTrailingFiller] threw the last piece away.
     */
    data class Result(
        val pieces: List<Piece>,
        val tailMs: Long,
        val wholeFallback: Boolean,
        val tailLoudness: Float?,
        val droppedFiller: Boolean,
    )

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

    /** Decodes what's left after the last cut: the whole recording if there was none. */
    fun finish(): Result {
        val started = System.nanoTime()
        if (size > fed) {
            val last = FloatArray(Segmenter.WINDOW)
            audio.copyInto(last, 0, fed, size)
            vad.acceptWaveform(last)
        }
        vad.flush()
        drain(atEnd = true)
        val whole = pieces.isEmpty()
        if (size - cut >= MIN_TAIL || (whole && size > 0)) {
            pieces += Piece(tailSpeech ?: Span(cut, size), decode(audio.copyOfRange(cut, size)))
            cut = size
        }
        val kept = dropTrailingFiller(pieces, size)
        return Result(kept, (System.nanoTime() - started) / 1_000_000, whole, tailLoudness(), kept.size < pieces.size)
    }

    private fun tailLoudness(): Float? {
        if (pieces.size < 2) return null
        fun rms(spans: List<Span>): Double {
            var sumSq = 0.0
            var n = 0
            for (span in spans) for (i in span.start until minOf(span.end, size)) {
                sumSq += audio[i] * audio[i]
                n++
            }
            return if (n == 0) 0.0 else sqrt(sumSq / n)
        }
        val rest = rms(pieces.dropLast(1).map { it.span })
        return if (rest > 0.0) (rms(listOf(pieces.last().span)) / rest).toFloat() else null
    }

    fun release() = vad.release()

    /**
     * For every stretch of speech the VAD has closed, cuts in the pause after
     * it and decodes from the last cut to this one. True if it decoded any.
     * At the end, the flushed last stretch has no pause after it yet: it's
     * left to the tail.
     */
    private fun drain(atEnd: Boolean = false): Boolean {
        var any = false
        while (!vad.empty()) {
            val seg = vad.front()
            val end = minOf(size, seg.start + seg.samples.size)
            vad.pop()
            // A piece's span is its speech, not its cut range, so the join rules
            // still see how long the pause between two pieces was.
            val speech = Span(maxOf(cut, seg.start), end)
            if (atEnd && size - end < MIN_TAIL) {
                tailSpeech = speech
                continue
            }
            // A 20 s forced cut has no pause after it: cut at its end.
            val at = if (seg.samples.size >= FORCED_CUT) end else quietestPoint(audio, end, size)
            if (at - cut < MIN_TAIL) continue
            pieces += Piece(speech, decode(audio.copyOfRange(cut, at)))
            cut = at
            any = true
        }
        return any
    }

    companion object {
        /** Under 0.1 s isn't worth a decode of its own. */
        const val MIN_TAIL = Wav.RATE / 10
        private const val FORCED_CUT = Wav.RATE * 19
    }
}
