package com.ethanward.flowtype.asr

/**
 * Drops an "okay" run onto the end of the last sentence with no comma: "On my
 * way okay." Parakeet hears handling noise as "okay" too, and when it is close
 * enough to the last word it lands in the same piece, where [dropTrailingFiller]
 * can't see it (history, 2026-11: "I love you too okay.", "On my way okay.").
 * An "okay" the sentence needs is kept: after a word it naturally follows
 * ("That's okay.", "It is okay", "you okay"), after a comma, as a question
 * ("Is the dog okay?"), or when it's most of the message.
 */
fun dropGluedOkay(text: String): String {
    val m = GLUED_OKAY.find(text) ?: return text
    val before = m.groupValues[1]
    val prev = before.lowercase().replace('’', '\'')
    if (prev in OKAY_FOLLOWS || prev.endsWith("'s") || prev.endsWith("'re") || prev.endsWith("'m")) return text
    val head = text.substring(0, m.range.first + before.length)
    if (head.split(Regex("\\s+")).count { it.isNotEmpty() } < 2) return text
    return head + m.groupValues[2]
}

/** "<word> okay." at the very end, the two separated by spaces only. */
private val GLUED_OKAY = Regex("""([\p{L}'’]+)\s+(?i:okay|ok)([.!]?)\s*$""")

/** Words "okay" follows in a real sentence. */
private val OKAY_FOLLOWS = setOf(
    "is", "isn't", "are", "aren't", "am", "was", "wasn't", "were", "weren't", "be", "been", "being",
    "seem", "seems", "seemed", "look", "looks", "looked", "feel", "feels", "felt", "sound", "sounds", "sounded",
    "all", "not", "totally", "perfectly", "pretty", "really", "so", "very", "just", "quite", "mostly", "kinda",
    "you", "u", "we", "they", "he", "she", "it", "that", "this", "everyone", "everybody", "everything",
    "say", "said", "says", "an", "the", "okay", "ok", "it's", "that's", "fine",
)
