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
