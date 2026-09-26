package com.ethanward.flowtype.cleanup

/**
 * Checks on the cleanup model's answer (PLAN §4.5): the fix for "it answered
 * my question instead of typing it". Any failure means the local text is typed
 * instead. Nothing is inserted until the whole answer has passed.
 */
object Guards {
    enum class Verdict { OK, TOO_LONG, TOO_SHORT, ASSISTANT, EMPTY }

    private val FILLERS = setOf("um", "umm", "uh", "uhh", "uhm", "er", "erm", "ah", "hmm", "mm", "mhm")

    /** Openings of a reply rather than a cleaned-up dictation. */
    private val ASSISTANT_OPENINGS = listOf(
        "sure", "certainly", "of course", "absolutely", "here's", "here is", "here are",
        "i can't", "i cannot", "i can not", "i'm sorry", "i am sorry", "sorry, i", "as an ai",
        "i'd be happy", "i would be happy", "happy to help", "great question", "i'm unable", "i am unable",
    )

    fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}'$%@.-]+"))
            .map { it.trim('.', '-', '\'') }
            .filter { it.isNotEmpty() }

    /** Words that carry meaning: fillers ("um", "uh") and "you know" dropped. */
    fun contentWords(text: String): List<String> {
        val w = words(text).filterNot { it in FILLERS }
        val out = ArrayList<String>(w.size)
        var i = 0
        while (i < w.size) {
            if (w[i] == "you" && w.getOrNull(i + 1) == "know") {
                i += 2
                continue
            }
            out += w[i]
            i++
        }
        return out
    }

    /**
     * Length: over 1.6× the input's content words (with 3 words of slack for
     * short inputs) is too long; under 0.5× is too short, but only for inputs of
     * 8+ words, since short ones, self-corrections and numbers shrink a lot
     * legitimately.
     */
    fun length(input: String, output: String): Verdict {
        val inW = contentWords(input).size
        val outW = words(output).size
        if (outW == 0) return if (inW == 0) Verdict.OK else Verdict.EMPTY
        if (outW > maxOf(inW * 1.6, inW + 3.0)) return Verdict.TOO_LONG
        if (inW >= 8 && outW < inW * 0.5) return Verdict.TOO_SHORT
        return Verdict.OK
    }

    /**
     * Whether [outputStart] opens like an assistant's reply when the input
     * didn't. Runs on the first streamed words, so the request can be
     * cancelled early.
     */
    fun soundsLikeAssistant(input: String, outputStart: String): Boolean {
        val out = normalizeStart(outputStart)
        val inp = normalizeStart(input)
        return ASSISTANT_OPENINGS.any { opening ->
            out.startsWith(opening) && isWordEnd(out, opening.length) && !inp.startsWith(opening)
        }
    }

    /** Enough of the answer to judge its opening: three words, or the whole answer. */
    fun canJudgeStart(outputStart: String, finished: Boolean): Boolean =
        finished || words(outputStart).size >= 3

    fun check(input: String, output: String): Verdict = when {
        soundsLikeAssistant(input, output) -> Verdict.ASSISTANT
        else -> length(input, output)
    }

    private fun normalizeStart(s: String) =
        s.trimStart().trimStart('"', '“', '\'', '*').lowercase().replace('’', '\'')

    private fun isWordEnd(s: String, at: Int) = at >= s.length || !s[at].isLetterOrDigit()
}
