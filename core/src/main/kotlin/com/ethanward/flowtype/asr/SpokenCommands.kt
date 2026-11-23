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

    private val hours = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

    /** "ten o'clock" or "10 o'clock": a time is written 10:00 (Ethan, 2026-09-27). */
    private val oClock = Regex("$W(${hours.joinToString("|")}|1[0-2]|[1-9]) o[’']? ?clock$E", RegexOption.IGNORE_CASE)

    fun apply(text: String): String {
        var t = oClock.replace(text) { m ->
            val h = m.groupValues[1]
            "${h.toIntOrNull() ?: (hours.indexOf(h.lowercase()) + 1)}:00"
        }
        for ((regex, mark) in marks) t = regex.replace(t, mark)
        t = breaks.replace(t) { m -> if (m.groupValues[1].equals("paragraph", true)) "\n\n" else "\n" }
        // A new sentence after ? ! or a line break starts with a capital.
        t = Regex("([?!]\\s+|\\n)(\\p{Ll})").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t.trim(' ')
    }
}
