package com.ethanward.flowtype.dictionary

/**
 * Auto-learn (PLAN §4.4): finds the one word of a dictation you replaced
 * after it was typed ("bamboo" → "Bambu"), so Flowtype can offer to add it to
 * the dictionary. It never adds anything itself.
 *
 * Deliberately narrow: the dictation's words must all still be there, in
 * order, except exactly one, so ordinary editing around it isn't mistaken for
 * a correction.
 */
object Corrections {
    /** Shorter dictations give too little around the word to be sure it's ours. */
    private const val MIN_WORDS = 3

    /**
     * The word that replaced one of [typed]'s in [now] (the field's text near
     * the cursor), or null. Words already in [known] aren't offered again.
     */
    fun find(typed: String, now: CharSequence, known: Set<String>): String? {
        val t = words(typed)
        val c = words(now.toString())
        if (t.size < MIN_WORDS || c.size < t.size) return null
        for (start in 0..c.size - t.size) {
            var diff = -1
            var ok = true
            for (j in t.indices) {
                val was = t[j].bare
                val isNow = c[start + j].bare
                if (was == isNow) continue
                // A case change counts only for a capital in mid-sentence ("bambu" → "Bambu").
                val midSentenceCapital = j > 0 && !t[j - 1].endsSentence && isNow.any { it.isUpperCase() }
                if (was.equals(isNow, ignoreCase = true) && !midSentenceCapital) continue
                if (diff >= 0) {
                    ok = false
                    break
                }
                diff = j
            }
            if (!ok || diff < 0) continue
            val word = c[start + diff].bare
            if (word.count { it.isLetter() } < 2 || known.any { it == word }) return null
            // Words are for spellings the phone gets wrong: names, brands, PETG. An
            // ordinary lowercase word ("Bambu" put back to "bamboo") needs no entry.
            if (word.none { it.isUpperCase() || it.isDigit() }) return null
            return word
        }
        return null
    }

    private class Word(val bare: String, val endsSentence: Boolean)

    private fun words(text: String): List<Word> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { raw ->
            Word(raw.trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }, (raw.trimEnd('"', '”', ')').lastOrNull() ?: ' ') in ".!?")
        }.filter { it.bare.isNotEmpty() }
}
