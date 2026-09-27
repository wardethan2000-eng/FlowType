package com.ethanward.flowtype.cleanup

/**
 * Dictations short and plain enough that the phone's own text is already what
 * cleanup would give: typed at once instead of waiting a second for the model.
 * Parakeet punctuates and capitalizes, the dictionary and spoken commands run
 * locally, and insertion fits the case to the text before the cursor, so what
 * the model adds on these is only the wait. Anything it might change (a filler,
 * a correction, a number, spoken formatting, a stutter) still goes to it.
 */
object AlreadyClean {
    const val MAX_WORDS = 8

    /** Words the model would drop, resolve, or turn into a mark or a figure. */
    private val CUES = setOf(
        // hesitations and hedges
        "um", "umm", "uh", "uhh", "uhm", "er", "erm", "ah", "hmm", "mm", "mhm", "like", "basically",
        // self-corrections
        "no", "sorry", "actually", "wait", "mean", "scratch", "rather", "correction",
        // numbers, amounts and times
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "billion", "dollar", "dollars", "cents", "percent", "o'clock",
        // spoken formatting SpokenCommands leaves to the model
        "period", "dash", "hyphen", "quote", "unquote", "paren", "parenthesis", "bullet", "numbered",
        "dot", "slash", "underscore", "line", "paragraph",
    )

    private val PHRASES = listOf("you know", "sort of", "kind of", "full stop")

    /** True when [text] can be typed as it is in a field of this [style]. */
    fun check(text: String, style: String): Boolean {
        // Search wants no final punctuation and no capital: the model's job.
        if (style == AppStyle.SEARCH) return false
        val words = Guards.words(text)
        if (words.isEmpty() || words.size > MAX_WORDS) return false
        if (text.any { it.isDigit() }) return false
        if (words.any { it in CUES }) return false
        val lower = text.lowercase()
        if (PHRASES.any { Regex("\\b$it\\b").containsMatchIn(lower) }) return false
        if (words.zipWithNext().any { (a, b) -> a == b }) return false
        return true
    }

    /**
     * The phone's text, finished as the model would for this style: a single
     * short sentence in a messaging app loses its final period.
     */
    fun finish(text: String, style: String): String {
        val t = text.trim()
        if (style != AppStyle.MESSAGING || !t.endsWith('.') || t.endsWith("..")) return t
        val body = t.dropLast(1)
        return if (body.any { it in ".?!\n" }) t else body
    }
}
