package com.ethanward.flowtype.dictionary

import org.json.JSONArray
import org.json.JSONObject

/** "When I say [from], type [to]." */
data class Replacement(val from: String, val to: String)

/** "When I say [trigger], type [text]": a saved block such as an address, typed as is. */
data class Snippet(val trigger: String, val text: String)

/**
 * The personal dictionary (PLAN §4.4): Words are exact spellings; Replacements
 * turn what was heard into what should be typed; Snippets expand a spoken
 * trigger into saved text that cleanup never touches.
 */
data class Dictionary(
    val words: List<String> = emptyList(),
    val replacements: List<Replacement> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
) {
    val isEmpty: Boolean get() = words.isEmpty() && replacements.isEmpty() && snippets.isEmpty()

    fun withWord(word: String, replacing: String? = null): Dictionary {
        val w = cleanWord(word)
        val kept = words.filterNot { it.equals(w, ignoreCase = true) || (replacing != null && it == replacing) }
        return copy(words = (kept + w).sortedBy { it.lowercase() })
    }

    fun withoutWord(word: String) = copy(words = words - word)

    fun withReplacement(r: Replacement, replacing: Replacement? = null): Dictionary {
        val c = Replacement(cleanPhrase(r.from), r.to.trim())
        val kept = replacements.filterNot { it.from.equals(c.from, ignoreCase = true) || it == replacing }
        return copy(replacements = (kept + c).sortedBy { it.from.lowercase() })
    }

    fun withoutReplacement(r: Replacement) = copy(replacements = replacements - r)

    fun withSnippet(sn: Snippet, replacing: Snippet? = null): Dictionary {
        val c = Snippet(cleanPhrase(sn.trigger), sn.text.trim())
        val kept = snippets.filterNot { it.trigger.equals(c.trigger, ignoreCase = true) || it == replacing }
        return copy(snippets = (kept + c).sortedBy { it.trigger })
    }

    fun withoutSnippet(sn: Snippet) = copy(snippets = snippets - sn)

    /** Import: add everything from [other]; on a clash, [other] wins. */
    fun merge(other: Dictionary): Dictionary {
        var d = this
        other.words.forEach { d = d.withWord(it) }
        other.replacements.forEach { d = d.withReplacement(it) }
        other.snippets.forEach { d = d.withSnippet(it) }
        return d
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("words", JSONArray(words))
        .put("replacements", JSONArray(replacements.map { JSONObject().put("from", it.from).put("to", it.to) }))
        .put("snippets", JSONArray(snippets.map { JSONObject().put("trigger", it.trigger).put("text", it.text) }))

    companion object {
        const val FORMAT = "flowtype-dictionary"

        fun cleanWord(s: String) = s.trim().replace(Regex("\\s+"), " ")

        /** Spoken forms compare without case or extra spaces. */
        fun cleanPhrase(s: String) = cleanWord(s).lowercase()

        /** null if fine, else what's wrong, in words for the screen. */
        fun problemWithWord(s: String): String? = when {
            cleanWord(s).isEmpty() -> "Type the word the way it should be spelled."
            s.contains('\n') -> "One word or name per entry."
            cleanWord(s).length > 60 -> "That's long for a word. Use a replacement for phrases."
            else -> null
        }

        fun problemWithReplacement(from: String, to: String): String? = when {
            cleanPhrase(from).count { it.isLetterOrDigit() } < 2 -> "Type what you say (at least two letters)."
            to.isBlank() -> "Type what Flowtype should write instead."
            cleanPhrase(from) == to.trim().lowercase() && cleanWord(from) == to.trim() -> "Those are the same."
            else -> null
        }

        fun problemWithSnippet(trigger: String, text: String): String? = when {
            cleanPhrase(trigger).count { it.isLetterOrDigit() } < 2 -> "Type what you'll say (at least two letters)."
            text.isBlank() -> "Type the text it should put in."
            text.length > 2_000 -> "That's too long for a snippet."
            else -> null
        }

        /** Reads the file format; unknown fields are ignored, so later versions still load. */
        fun fromJson(json: JSONObject): Dictionary {
            require(json.optString("format", FORMAT) == FORMAT) { "not a Flowtype dictionary" }
            val words = json.optJSONArray("words")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val reps = json.optJSONArray("replacements")?.let { a ->
                List(a.length()) { a.getJSONObject(it) }.map { Replacement(it.getString("from"), it.getString("to")) }
            } ?: emptyList()
            val snippets = json.optJSONArray("snippets")?.let { a ->
                List(a.length()) { a.getJSONObject(it) }.map { Snippet(it.getString("trigger"), it.getString("text")) }
            } ?: emptyList()
            return Dictionary().merge(Dictionary(
                words.filter { problemWithWord(it) == null },
                reps.filter { problemWithReplacement(it.from, it.to) == null },
                snippets.filter { problemWithSnippet(it.trigger, it.text) == null },
            ))
        }
    }
}
