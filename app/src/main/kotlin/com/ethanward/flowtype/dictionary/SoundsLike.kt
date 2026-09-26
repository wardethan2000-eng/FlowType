package com.ethanward.flowtype.dictionary

/**
 * "Sounds like" matching for dictionary Words (PLAN §4.4, Phase 2): catches
 * names the recognizer spelled as ordinary words ("bamboo" for "Bambu",
 * "deck all forge" for "DecalForge"), offline.
 *
 * A heard stretch of 1–3 words matches a Word only when both hold:
 * - the same sound key (a simplified Metaphone), and
 * - letters within 40% edit distance of the Word's.
 * Words under 5 letters and acronyms ("PETG", read out as letters) are never
 * matched this way; they'd turn ordinary words into names.
 */
object SoundsLike {
    const val MIN_LETTERS = 5

    fun eligible(word: String): Boolean {
        val letters = letters(word)
        if (letters.length < MIN_LETTERS) return false
        val isAcronym = word.filter { it.isLetter() }.all { it.isUpperCase() }
        return !isAcronym
    }

    fun letters(s: String) = s.lowercase().filter { it in 'a'..'z' }

    fun matches(heard: String, word: String): Boolean {
        val a = letters(heard)
        val b = letters(word)
        if (a.isEmpty() || a == b) return false
        val key = key(b)
        if (key.length < 2 || key(a) != key) return false
        return distance(a, b) <= maxOf(1, (b.length * 0.4).toInt())
    }

    /** A rough sound key: consonant sounds only, doubled letters collapsed. */
    fun key(word: String): String {
        var w = letters(word)
        if (w.isEmpty()) return ""
        for ((from, to) in START) if (w.startsWith(from)) {
            w = to + w.drop(from.length)
            break
        }
        for ((from, to) in DIGRAPHS) w = w.replace(from, to)
        val out = StringBuilder()
        for ((i, c) in w.withIndex()) {
            val next = w.getOrNull(i + 1)
            val code: Char? = when (c) {
                'a', 'e', 'i', 'o', 'u', 'y', 'w', 'h' -> if (i == 0 && c !in "hwy") 'A' else null
                'c' -> if (next != null && next in "eiy") 's' else 'k'
                'g' -> if (next != null && next in "eiy") 'j' else 'k'
                'q' -> 'k'
                'z' -> 's'
                'v' -> 'f'
                'd' -> 't'
                'b' -> if (i == w.length - 1 && w.getOrNull(i - 1) == 'm') null else 'b'
                else -> c
            }
            if (code != null && out.lastOrNull() != code) out.append(code)
        }
        return out.toString()
    }

    private val START = listOf("kn" to "n", "gn" to "n", "pn" to "n", "wr" to "r", "wh" to "w", "x" to "s")
    private val DIGRAPHS = listOf(
        "sch" to "sk", "tch" to "x", "ch" to "x", "sh" to "x", "ph" to "f", "th" to "0",
        "ck" to "k", "gh" to "", "qu" to "kw", "dg" to "j", "x" to "ks",
    )

    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private val TOKEN = Regex("[\\p{L}][\\p{L}'’]*")

    /**
     * Replaces heard stretches that sound like one of [words] with its
     * spelling. Returns the text and how many it changed.
     */
    fun apply(text: String, words: List<String>): Pair<String, Int> {
        val targets = words.filter(::eligible)
        if (targets.isEmpty()) return text to 0
        val tokens = TOKEN.findAll(text).toList()
        val out = StringBuilder()
        var copied = 0
        var changed = 0
        var i = 0
        while (i < tokens.size) {
            var hit: Pair<Int, String>? = null
            for (n in 3 downTo 1) {
                if (i + n > tokens.size) continue
                // Only words next to each other, separated by spaces.
                val gapOk = (i until i + n - 1).all { k -> text.substring(tokens[k].range.last + 1, tokens[k + 1].range.first).isBlank() }
                if (!gapOk) continue
                val heard = tokens.subList(i, i + n).joinToString("") { it.value }
                val word = targets.firstOrNull { matches(heard, it) } ?: continue
                hit = n to word
                break
            }
            if (hit == null) {
                i++
                continue
            }
            val (n, word) = hit
            out.append(text, copied, tokens[i].range.first).append(word)
            copied = tokens[i + n - 1].range.last + 1
            changed++
            i += n
        }
        out.append(text, copied, text.length)
        return out.toString() to changed
    }
}
