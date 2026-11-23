package com.ethanward.flowtype.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.ethanward.flowtype.insert.InsertionRules
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
    private val vad = newVad(vadModel)

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

        /** Silero VAD as dictation uses it: cut at pauses over 0.5 s, force a cut at 20 s. */
        fun newVad(model: File) = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.path,
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
    }
}

/**
 * The middle of the quietest 20 ms window in [from, to): where to cut a
 * recording inside a pause, so a soft word at its edge isn't split. [to] when
 * the range is shorter than a window.
 */
fun quietestPoint(audio: FloatArray, from: Int, to: Int, window: Int = 320): Int {
    if (to - from < window) return to
    var best = from
    var bestEnergy = Double.MAX_VALUE
    var i = from
    while (i + window <= to) {
        var e = 0.0
        for (j in i until i + window) e += audio[j] * audio[j]
        if (e < bestEnergy) {
            bestEnergy = e
            best = i
        }
        i += window / 2
    }
    return best + window / 2
}

/** One decoded piece of a recording. */
data class Piece(val span: Span, val text: String)

/**
 * Joins pieces decoded one at a time (PLAN §4.3 join rules). Each piece was
 * decoded on its own, so the model may have ended it with a full stop
 * mid-sentence and capitalized the next one.
 *
 * - Words repeated across a seam (padding can hear the same word twice) are
 *   dropped from the second piece.
 * - After a short pause (under [shortGap] samples), a trailing full stop is
 *   dropped and the next word lowercased, unless it's "I", an acronym, or one
 *   of [keepCase] (dictionary words).
 *
 * AI cleanup fixes whatever is left when it's on.
 */
fun joinPieces(pieces: List<Piece>, keepCase: Set<String> = emptySet(), shortGap: Int = 700 * 16): String {
    val out = StringBuilder()
    var prev: Piece? = null
    for (piece in pieces) {
        var text = piece.text.trim()
        if (text.isEmpty()) continue
        val before = prev
        if (before == null || out.isEmpty()) {
            out.append(text)
            prev = piece
            continue
        }
        text = dropRepeatedWords(out, text)
        if (text.isEmpty()) {
            prev = piece
            continue
        }
        val gap = piece.span.start - before.span.end
        if (gap < shortGap && out.endsWith(".") && !out.endsWith("..")) {
            out.setLength(out.length - 1)
            text = InsertionRules.lowercaseFirstWord(text, keepCase)
        }
        out.append(' ').append(text)
        prev = piece
    }
    return out.toString()
}

/**
 * Drops the last piece when it's only a filler word ("Mm-hmm.", "Yeah.") heard
 * in a separate burst of sound that runs into the ✓ tap: the phone moving in
 * the hand as you reach for it. Parakeet has no "no speech" answer, so it
 * decodes that noise as its most common short word, and the VAD calls it
 * speech too. A filler said on purpose and followed by any wait before ✓ stops
 * short of [total] by more than [touch] samples and is kept. [minGap] keeps a
 * piece that follows a 20 s forced cut, where there was no pause at all.
 *
 * [total] is the recording's length in samples.
 */
fun dropTrailingFiller(
    pieces: List<Piece>,
    total: Int,
    touch: Int = 150 * 16,
    minGap: Int = 100 * 16,
): List<Piece> {
    if (pieces.size < 2) return pieces
    val last = pieces.last()
    val before = pieces[pieces.size - 2]
    if (last.span.end < total - touch || last.span.start - before.span.end < minGap) return pieces
    val words = last.text.split(' ').map(::bare).filter { it.isNotEmpty() }
    if (words.isEmpty() || !words.all { it in FILLERS }) return pieces
    return pieces.dropLast(1)
}

/** What Parakeet makes of handling noise, as [bare] words. */
private val FILLERS = setOf(
    "yeah", "mm-hmm", "mhm", "mm", "mmm", "hmm", "hm", "uh-huh", "uh", "um", "ah", "oh",
)

/** Plain join, for comparing against the rules. */
fun joinSegments(texts: List<String>): String =
    texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

private fun bare(word: String) = word.lowercase().trim { !it.isLetterOrDigit() && it != '\'' }

/** Drops up to 3 words at the start of [next] that repeat the end of [sofar]. */
private fun dropRepeatedWords(sofar: CharSequence, next: String): String {
    val tail = sofar.split(' ').takeLast(3).map(::bare)
    val head = next.split(' ')
    for (n in minOf(3, tail.size, head.size) downTo 1) {
        if (tail.takeLast(n) == head.take(n).map(::bare) && tail.takeLast(n).all { it.isNotEmpty() }) {
            return head.drop(n).joinToString(" ")
        }
    }
    return next
}
