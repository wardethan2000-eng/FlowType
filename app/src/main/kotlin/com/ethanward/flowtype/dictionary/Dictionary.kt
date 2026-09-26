package com.ethanward.flowtype.dictionary

import org.json.JSONArray
import org.json.JSONObject

/** "When I say [from], type [to]." */
data class Replacement(val from: String, val to: String)

/**
 * The personal dictionary (PLAN §4.4): Words are exact spellings; Replacements
 * turn what was heard into what should be typed. Snippets come in Phase 3.
 */
data class Dictionary(
    val words: List<String> = emptyList(),
    val replacements: List<Replacement> = emptyList(),
) {
    val isEmpty: Boolean get() = words.isEmpty() && replacements.isEmpty()

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

    /** Import: add everything from [other]; on a clash, [other] wins. */
    fun merge(other: Dictionary): Dictionary {
        var d = this
        other.words.forEach { d = d.withWord(it) }
        other.replacements.forEach { d = d.withReplacement(it) }
        return d
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("words", JSONArray(words))
        .put("replacements", JSONArray(replacements.map { JSONObject().put("from", it.from).put("to", it.to) }))

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

        /** Reads the file format; unknown fields are ignored, so later versions still load. */
        fun fromJson(json: JSONObject): Dictionary {
            require(json.optString("format", FORMAT) == FORMAT) { "not a Flowtype dictionary" }
            val words = json.optJSONArray("words")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val reps = json.optJSONArray("replacements")?.let { a ->
                List(a.length()) { a.getJSONObject(it) }.map { Replacement(it.getString("from"), it.getString("to")) }
            } ?: emptyList()
            return Dictionary().merge(Dictionary(words.filter { problemWithWord(it) == null }, reps.filter {
                problemWithReplacement(it.from, it.to) == null
            }))
        }
    }
}
