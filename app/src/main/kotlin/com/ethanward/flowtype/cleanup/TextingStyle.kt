package com.ethanward.flowtype.cleanup

/**
 * How a text in a messaging app is punctuated: the user's pick. Applied on the
 * phone to whatever is about to be typed (the model's answer or the phone's own
 * text), so every provider, and no provider at all, gives the same result.
 * Question marks, exclamation points and "..." are always kept.
 */
enum class TextingStyle(val id: String, val label: String, val example: String) {
    /** Only a text that is one sentence loses its period. */
    ONE_SENTENCE("one_sentence", "Full punctuation", "Running late. Be there at five."),

    /** The last period of the text goes; the ones between sentences stay. */
    NO_LAST_PERIOD("no_last_period", "No period at the end", "Running late. Be there at five"),

    /** No periods: each sentence on its own line. */
    LINES("lines", "One line per sentence", "Running late\nBe there at five"),

    /** No periods and no capital to start a sentence; names and "I" keep theirs. */
    CASUAL("casual", "Fully casual", "running late, be there at five");

    fun apply(text: String): String {
        val lines = text.trim().split('\n').map { sentences(it) }
        return when (this) {
            ONE_SENTENCE -> {
                if (lines.sumOf { it.size } == 1) dropPeriod(text.trim()) else text.trim()
            }
            NO_LAST_PERIOD -> dropPeriod(text.trim())
            LINES -> lines.joinToString("\n") { line -> line.joinToString("\n") { dropPeriod(it) } }
            CASUAL -> lines.joinToString("\n") { line ->
                buildString {
                    for ((i, sentence) in line.withIndex()) {
                        val dropped = dropPeriod(sentence)
                        append(lowerStart(dropped))
                        if (i < line.lastIndex) append(if (dropped.length < sentence.length) ", " else " ")
                    }
                }
            }
        }
    }

    companion object {
        val DEFAULT = NO_LAST_PERIOD

        fun byId(id: String?): TextingStyle = TextingStyle.entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Words whose period belongs to them, not to the sentence. */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "st", "ave", "apt", "mt", "jr", "sr", "vs", "etc", "inc", "ltd", "co",
            "approx", "e.g", "i.e", "a.m", "p.m",
        )

        /**
         * Sentence openers safe to lowercase. Anything else keeps its capital,
         * since it may be a name: so "may", "mark", "bill" and "rose" are left out.
         */
        private val COMMON = setOf(
            "a", "about", "actually", "after", "again", "all", "almost", "already", "also", "always", "am", "an", "and",
            "any", "anyway", "are", "as", "at", "awesome", "back", "be", "because", "been", "before", "both", "bring",
            "but", "by", "bye", "call", "can", "come", "cool", "could", "did", "do", "does", "done", "each", "either",
            "even", "every", "everyone", "everything", "fine", "for", "from", "get", "give", "going", "gonna", "good",
            "got", "gotcha", "great", "had", "haha", "has", "have", "he", "heading", "hello", "her", "here", "hey", "hi",
            "his", "home", "hope", "how", "if", "in", "is", "it", "just", "keep", "kind", "last", "later", "leaving",
            "let", "like", "lol", "look", "looks", "love", "make", "maybe", "me", "meet", "might", "more", "most", "my",
            "need", "never", "next", "nice", "no", "nope", "not", "nothing", "now", "of", "oh", "ok", "okay", "omg", "on",
            "once", "one", "only", "or", "our", "out", "over", "perfect", "please", "probably", "really", "right",
            "running", "same", "see", "send", "she", "should", "since", "so", "some", "someone", "something", "sorry",
            "sounds", "still", "sure", "take", "talk", "tell", "text", "thank", "thanks", "that", "the", "their", "then",
            "there", "these", "they", "this", "those", "though", "to", "today", "tomorrow", "tonight", "too", "try",
            "until", "up", "wait", "want", "was", "we", "well", "were", "what", "whatever", "when", "where", "which",
            "while", "who", "why", "will", "with", "without", "wow", "would", "yeah", "yep", "yes", "yesterday", "yet",
            "you", "your",
        )

        /** [line] cut after each sentence end that is followed by a space. */
        fun sentences(line: String): List<String> {
            val out = ArrayList<String>()
            var start = 0
            var i = 0
            while (i < line.length) {
                if (line[i] in ".?!" && i + 1 < line.length && line[i + 1].isWhitespace()) {
                    val piece = line.substring(start, i + 1)
                    if (line[i] != '.' || !keepsPeriod(piece)) {
                        out.add(piece.trim())
                        start = i + 1
                    }
                }
                i++
            }
            val rest = line.substring(start).trim()
            if (rest.isNotEmpty() || out.isEmpty()) out.add(rest)
            return out
        }

        /** [sentence] without its final period, unless the period is an abbreviation's or part of "...". */
        fun dropPeriod(sentence: String): String =
            if (sentence.endsWith('.') && !keepsPeriod(sentence)) sentence.dropLast(1) else sentence

        /** Ends in "...", an abbreviation ("etc.", "p.m.") or an initial ("J."). */
        private fun keepsPeriod(endsWithPeriod: String): Boolean {
            val body = endsWithPeriod.dropLast(1)
            if (body.endsWith('.') || body.isEmpty()) return true
            val word = body.takeLastWhile { !it.isWhitespace() }.trimStart { !it.isLetterOrDigit() }
            return word.lowercase() in ABBREVIATIONS || (word.length == 1 && word[0].isUpperCase() && word != "I")
        }

        /** Lowercases the first word when it is an everyday one; "I", names and acronyms stay. */
        private fun lowerStart(sentence: String): String {
            val at = sentence.indexOfFirst { it.isLetter() }
            if (at < 0 || !sentence[at].isUpperCase()) return sentence
            // Up to the first non-letter, so "That's" and "Let's" count as "that" and "let".
            val word = sentence.substring(at).takeWhile { it.isLetter() }
            if (word.drop(1).any { it.isUpperCase() } || word.lowercase() !in COMMON) return sentence
            return sentence.substring(0, at) + sentence[at].lowercaseChar() + sentence.substring(at + 1)
        }
    }
}
