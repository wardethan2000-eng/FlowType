package com.ethanward.flowtype.insert

import android.text.InputType

/** Pure rules around inserting; JVM-tested. */
object InsertionRules {
    /**
     * Whether the text before the cursor proves our commit landed. null when
     * the app wouldn't say (no surrounding text): unknown, not failed.
     */
    fun landed(textBeforeCursor: CharSequence?, inserted: String): Boolean? {
        if (textBeforeCursor == null) return null
        return textBeforeCursor.endsWith(inserted)
    }

    /**
     * A leading space when we'd otherwise glue onto the previous word. Nothing
     * at the start of a field, after whitespace or after an opening bracket.
     */
    fun withLeadingSpace(text: String, charBefore: Char?): String {
        if (text.isEmpty() || charBefore == null) return text
        if (charBefore.isWhitespace() || charBefore in "([{\"'“‘") return text
        if (text.first().isWhitespace()) return text
        return " $text"
    }

    /** A field's new text and cursor for ACTION_SET_TEXT. */
    data class Splice(val text: String, val cursor: Int)

    /**
     * ACTION_SET_TEXT replaces the whole field, so it's allowed only when it
     * can't destroy anything (PLAN §4.6 step 2):
     * (a) the field is proven empty: it's showing its hint, or its text is
     *     exactly the hint; or
     * (b) the text is real and the selection is known and inside it.
     * Empty text with no hint, or an unknown cursor, returns null: not allowed.
     */
    fun setTextSplice(fieldText: CharSequence?, showingHint: Boolean, hint: CharSequence?, selStart: Int, selEnd: Int, insert: String): Splice? {
        val provenEmpty = showingHint || (fieldText != null && hint != null && fieldText.isNotEmpty() && fieldText.toString() == hint.toString())
        if (provenEmpty) {
            val t = insert.trimStart()
            return Splice(t, t.length)
        }
        if (fieldText.isNullOrEmpty()) return null
        if (selStart < 0 || selEnd < selStart || selEnd > fieldText.length) return null
        val before = fieldText.subSequence(0, selStart)
        val t = withLeadingSpace(insert.trimStart(), before.lastOrNull())
        return Splice(before.toString() + t + fieldText.subSequence(selEnd, fieldText.length), selStart + t.length)
    }

    /**
     * Whether a commit provably did nothing: the same text before and after,
     * both known. Only then may another path try, or the text could end up in
     * the field twice.
     */
    fun unchanged(before: CharSequence?, after: CharSequence?): Boolean =
        before != null && after != null && before.toString() == after.toString()

    /**
     * Fits dictated text to what's already before the cursor (PLAN §4.6 smart
     * spacing and case): a leading space when it would glue onto a word; a
     * capital after a sentence end; lowercase when
     * continuing a sentence, unless the word is "I", an acronym, CamelCase or
     * in [keepCase]. At the start of a field the text is left as it came (a
     * chat reply may be meant lowercase). With nothing known, it's unchanged.
     */
    fun fitToContext(text: String, before: CharSequence?, keepCase: Set<String> = emptySet()): String {
        if (text.isEmpty() || before == null) return text
        val last = before.lastOrNull { !it.isWhitespace() }
        val newLine = before.trimEnd(' ', '\t').endsWith('\n')
        val fitted = when {
            last == null -> text
            newLine || last in ".!?" -> text.replaceFirstChar { it.uppercaseChar() }
            last.isLetterOrDigit() || last in ",;:-–—" -> lowercaseFirstWord(text, keepCase)
            else -> text
        }
        return withLeadingSpace(fitted, before.lastOrNull())
    }

    /** Lowercases the first word unless it must keep its capital. */
    fun lowercaseFirstWord(text: String, keepCase: Set<String>): String {
        val word = text.substringBefore(' ').trimEnd { !it.isLetterOrDigit() }
        val keep = word.isEmpty() || word == "I" || word.startsWith("I'") || word.startsWith("I’") ||
            (word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }) ||
            word.drop(1).any { it.isUpperCase() } || word in keepCase
        return if (keep) text else text.replaceFirstChar { it.lowercaseChar() }
    }

    fun isPassword(inputType: Int): Boolean {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
