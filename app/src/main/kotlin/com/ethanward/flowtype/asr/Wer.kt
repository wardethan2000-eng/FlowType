package com.ethanward.flowtype.asr

/** Word error rate and dictionary-term hits, for the bench. */
object Wer {
    /** Lowercase words with punctuation removed ("Don't," → "don't"). */
    fun words(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}'\\s-]"), " ")
            .replace('-', ' ')
            .split(Regex("\\s+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }

    /** Word-level edit distance. */
    fun errors(reference: List<String>, hypothesis: List<String>): Int {
        var prev = IntArray(hypothesis.size + 1) { it }
        for (i in 1..reference.size) {
            val cur = IntArray(hypothesis.size + 1)
            cur[0] = i
            for (j in 1..hypothesis.size) {
                val sub = prev[j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[hypothesis.size]
    }

    fun rate(reference: String, hypothesis: String): Double {
        val ref = words(reference)
        if (ref.isEmpty()) return if (words(hypothesis).isEmpty()) 0.0 else 1.0
        return errors(ref, words(hypothesis)).toDouble() / ref.size
    }

    /**
     * How many of [terms]' occurrences in the reference (any case) the
     * hypothesis spelled exactly, case included. Returns (hits, occurrences).
     */
    fun termHits(reference: String, hypothesis: String, terms: List<String>): Pair<Int, Int> {
        var hits = 0
        var total = 0
        for (term in terms.map { it.trim() }.filter { it.isNotEmpty() }) {
            val pattern = "(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])"
            val inRef = Regex(pattern, RegexOption.IGNORE_CASE).findAll(reference).count()
            val exact = Regex(pattern).findAll(hypothesis).count()
            total += inRef
            hits += minOf(inRef, exact)
        }
        return hits to total
    }
}
