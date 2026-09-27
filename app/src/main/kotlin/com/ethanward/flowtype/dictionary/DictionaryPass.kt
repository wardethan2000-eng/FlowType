package com.ethanward.flowtype.dictionary

/**
 * Applies the dictionary to recognized text, offline and deterministically
 * (PLAN §4.4 passes 1 and 2; with no cleanup yet they run back to back):
 *
 * 1. Replacements: whole-word, any case, and the words of a phrase may be
 *    split by spaces or hyphens ("pet g", "Pet-G" → "PETG"). Longest phrase
 *    first, in one pass, so a replacement's output is never replaced again.
 * 2. Exact spellings: every Word, and every replacement output that has a
 *    capital in it, is written exactly as entered ("Decalforge" →
 *    "DecalForge", "petg" → "PETG"). A Word written in CamelCase also
 *    matches its parts heard apart ("decal forge"). All-lowercase outputs
 *    ("going to") aren't enforced, so they can still start a sentence.
 *
 * 3. With [soundsLike], Words heard as ordinary words ("bamboo" → "Bambu"),
 *    by [SoundsLike]'s sound key and spelling closeness.
 *
 * Before all of them, a snippet's trigger becomes a token (⟦S1⟧) that nothing
 * after, cleanup included, may change; [expand] puts the snippet's text in at
 * the very end.
 */
class DictionaryPass(dictionary: Dictionary, private val soundsLike: Boolean = false) {

    data class Result(val text: String, val replaced: Int, val respelled: Int, val soundAlike: Int = 0)

    private val words = dictionary.words

    private val snippets = dictionary.snippets.sortedByDescending { it.trigger.length }
    private val snippetRegex = alternation(snippets.map { phrasePattern(it.trigger) })

    private val replacements = dictionary.replacements.sortedByDescending { it.from.length }
    private val replaceRegex = alternation(replacements.map { phrasePattern(it.from) })

    private val spellings: List<String> =
        (dictionary.words + dictionary.replacements.map { it.to.trim() }.filter { t -> t.any { it.isUpperCase() } })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
    private val spellRegex = alternation(spellings.map { spellingPattern(it) })

    fun apply(text: String): Result {
        var replaced = 0
        var respelled = 0
        var out = text
        snippetRegex?.let { regex ->
            out = regex.replace(out) { m -> SnippetTokens.token(groupIndex(m)) }
        }
        replaceRegex?.let { regex ->
            out = regex.replace(out) { m ->
                val i = groupIndex(m)
                replaced++
                transferCase(m.value, replacements[i].to.trim())
            }
        }
        var soundAlike = 0
        if (soundsLike) {
            val (t, n) = SoundsLike.apply(out, words)
            out = t
            soundAlike = n
        }
        spellRegex?.let { regex ->
            out = regex.replace(out) { m ->
                val exact = spellings[groupIndex(m)]
                if (m.value != exact) respelled++
                exact
            }
        }
        return Result(out, replaced, respelled, soundAlike)
    }

    /** Each snippet token becomes its snippet's text: the last step before typing. */
    fun expand(text: String): String = SnippetTokens.expand(text) { snippets.getOrNull(it)?.text }

    companion object {
        private const val BEFORE = "(?<![\\p{L}\\p{N}])"
        private const val AFTER = "(?![\\p{L}\\p{N}])"

        private fun alternation(patterns: List<String>): Regex? {
            if (patterns.isEmpty()) return null
            val body = patterns.joinToString("|") { "($it)" }
            return Regex("$BEFORE(?:$body)$AFTER", setOf(RegexOption.IGNORE_CASE))
        }

        /** Which of the top-level alternatives matched (patterns contain no groups of their own). */
        private fun groupIndex(m: MatchResult): Int =
            (1 until m.groups.size).first { m.groups[it] != null } - 1

        /** Words of a phrase, separated by any run of spaces or hyphens. */
        fun phrasePattern(phrase: String): String =
            phrase.trim().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
                .joinToString("[\\s-]+") { Regex.escape(it) }

        /** The spelling itself, and for CamelCase its parts with an optional space or hyphen between. */
        fun spellingPattern(word: String): String {
            val exact = phrasePattern(word)
            val parts = camelParts(word)
            if (parts.size < 2) return exact
            val split = parts.joinToString("[\\s-]?") { Regex.escape(it) }
            return "$exact|$split"
        }

        /** "DecalForge" → [Decal, Forge]; "iPhone" → [i, Phone]; "PETG" → [PETG]. */
        fun camelParts(word: String): List<String> {
            if (word.any { it.isWhitespace() }) return listOf(word)
            return word.split(Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})"))
        }

        /**
         * A lowercase replacement that starts a sentence gets its capital:
         * "Gonna" → "Going to". Anything with capitals is kept as written.
         */
        fun transferCase(matched: String, replacement: String): String {
            if (replacement.isEmpty() || replacement.any { it.isUpperCase() }) return replacement
            return if (matched.firstOrNull()?.isUpperCase() == true) {
                replacement.replaceFirstChar { it.uppercaseChar() }
            } else replacement
        }
    }
}
