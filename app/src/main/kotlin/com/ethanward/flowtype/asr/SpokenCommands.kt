package com.ethanward.flowtype.asr

/**
 * Spoken formatting, applied to the phone's own text (PLAN §4.5: the local
 * text's punctuation comes from Parakeet plus this small rule-based pass).
 * With AI cleanup on, the model also handles these; this makes them work
 * offline too.
 *
 * Conservative on purpose: "period" and "full stop" are left alone (Parakeet
 * punctuates sentences itself, and "the trial period." is common), and
 * "new line" only counts when it stands apart ("list. New line. Eggs"), not in
 * "a new line of products".
 */
object SpokenCommands {
    private const val W = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"

    private val marks = listOf(
        Regex("\\s*${W}question mark$E[.,?!]*", RegexOption.IGNORE_CASE) to "?",
        Regex("\\s*${W}exclamation (?:point|mark)$E[.,?!]*", RegexOption.IGNORE_CASE) to "!",
        Regex("\\s*${W}comma$E[.,]*", RegexOption.IGNORE_CASE) to ",",
        Regex("\\s*${W}semicolon$E[.,]*", RegexOption.IGNORE_CASE) to ";",
        Regex("\\s*${W}colon$E[.,]*", RegexOption.IGNORE_CASE) to ":",
    )

    /** "new line"/"new paragraph" with punctuation or an edge on at least one side. */
    // Only the command words ignore case: under IGNORE_CASE, \\p{Lu} would match
    // lowercase letters too and "a new line of products" would break.
    private val breaks = Regex(
        "(?:(?<=^)|(?<=[.,!?:;])\\s*|\\s+)$W(?i:new (line|paragraph))$E(?:[.,:;]+\\s*|\\s*$|\\s+(?=\\p{Lu}))",
    )

    fun apply(text: String): String {
        var t = text
        for ((regex, mark) in marks) t = regex.replace(t, mark)
        t = breaks.replace(t) { m -> if (m.groupValues[1].equals("paragraph", true)) "\n\n" else "\n" }
        // A new sentence after ? ! or a line break starts with a capital.
        t = Regex("([?!]\\s+|\\n)(\\p{Ll})").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t.trim(' ')
    }
}
