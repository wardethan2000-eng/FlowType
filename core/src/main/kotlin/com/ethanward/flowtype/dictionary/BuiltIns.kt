package com.ethanward.flowtype.dictionary

/**
 * Replacements every user gets without adding them: common chat words that
 * Parakeet, trained mostly on read speech, hears as other words. Only ones seen
 * on the phone go here. A user's own replacement for the same phrase wins.
 */
object BuiltIns {
    val replacements = listOf(
        // "lol" said as a word (history, 2026-09-27: heard as "hello well" every time).
        Replacement("hello well", "lol"),
        // The same, run together (2026-10-01: one "lol" in four came out "Ellowell").
        Replacement("ellowell", "lol"),
        // Spelled out letter by letter, and "LOL"/"Lol" as heard: all typed as
        // "lol", lowercase even starting a message (Ethan, 2026-10-01).
        Replacement("l o l", "lol"),
        Replacement("lol", "lol"),
    )

    /** Outputs typed as written, never capitalized at the start of a sentence. */
    val alwaysLowercase = setOf("lol")

    /** The built-ins [own] doesn't already cover, to go after them. */
    fun notIn(own: List<Replacement>): List<Replacement> =
        replacements.filter { b -> own.none { it.from.equals(b.from, ignoreCase = true) } }
}
