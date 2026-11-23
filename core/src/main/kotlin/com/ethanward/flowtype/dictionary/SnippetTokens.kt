package com.ethanward.flowtype.dictionary

/**
 * The stand-in for a snippet while text goes through cleanup (PLAN §4.4): ⟦S1⟧,
 * ⟦S2⟧… The model is told to copy these unchanged, and an answer that drops or
 * repeats one is thrown away.
 */
object SnippetTokens {
    private val TOKEN = Regex("⟦S(\\d+)⟧")

    fun token(index: Int) = "⟦S${index + 1}⟧"

    /** The tokens in [text], in order. */
    fun of(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

    /** Whether [output] has exactly the tokens [input] had, each as often. */
    fun same(input: String, output: String): Boolean = of(input).sorted() == of(output).sorted()

    /** [text] without its tokens, for checks that look at the spoken words only. */
    fun strip(text: String): String = TOKEN.replace(text, "").replace(Regex(" {2,}"), " ").trim()

    fun expand(text: String, snippet: (Int) -> String?): String =
        TOKEN.replace(text) { m -> snippet(m.groupValues[1].toInt() - 1) ?: "" }
}
