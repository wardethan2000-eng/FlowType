package com.ethanward.flowtype.insert

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertionRulesTest {
    @Test
    fun landedOnlyWhenTextBeforeCursorEndsWithOurs() {
        assertEquals(true, InsertionRules.landed("Hi there. Hello world", " Hello world"))
        assertEquals(false, InsertionRules.landed("Hi there.", " Hello world"))
        // The app wouldn't say: unknown, never counted as a success.
        assertNull(InsertionRules.landed(null, "Hello"))
    }

    @Test
    fun leadingSpaceOnlyWhenGluingToAWord() {
        assertEquals(" hello", InsertionRules.withLeadingSpace("hello", 'd'))
        assertEquals(" hello", InsertionRules.withLeadingSpace("hello", '.'))
        assertEquals("hello", InsertionRules.withLeadingSpace("hello", ' '))
        assertEquals("hello", InsertionRules.withLeadingSpace("hello", '\n'))
        assertEquals("hello", InsertionRules.withLeadingSpace("hello", '('))
        assertEquals("hello", InsertionRules.withLeadingSpace("hello", null))
        assertEquals("", InsertionRules.withLeadingSpace("", 'a'))
    }

    @Test
    fun passwordFieldsAreRecognized() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(InsertionRules.isPassword(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(InsertionRules.isPassword(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(InsertionRules.isPassword(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(InsertionRules.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(InsertionRules.isPassword(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(InsertionRules.isPassword(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(InsertionRules.isPassword(InputType.TYPE_NULL))
    }

    @Test
    fun setTextIntoAProvenEmptyField() {
        // Showing its hint: the text is the placeholder, not content.
        assertEquals(InsertionRules.Splice("Hello", 5), InsertionRules.setTextSplice("Message", true, "Message", -1, -1, " Hello"))
        // Text equal to the hint counts as empty too.
        assertEquals(InsertionRules.Splice("Hello", 5), InsertionRules.setTextSplice("Message", false, "Message", 0, 0, "Hello"))
    }

    @Test
    fun setTextSplicesAtAKnownCursor() {
        assertEquals(InsertionRules.Splice("Hi there friend", 8), InsertionRules.setTextSplice("Hi friend", false, null, 2, 2, "there"))
        // Over a selection, with a space so it doesn't glue on.
        assertEquals(InsertionRules.Splice("Call Mike now", 9), InsertionRules.setTextSplice("Call Bob now", false, null, 4, 8, "Mike"))
    }

    @Test
    fun setTextIsRefusedWhenItCouldDestroyText() {
        // Empty with no hint: a rich editor may be hiding its content.
        assertNull(InsertionRules.setTextSplice("", false, null, 0, 0, "Hi"))
        assertNull(InsertionRules.setTextSplice(null, false, null, 0, 0, "Hi"))
        // Unknown or impossible cursor.
        assertNull(InsertionRules.setTextSplice("Draft text", false, null, -1, -1, "Hi"))
        assertNull(InsertionRules.setTextSplice("Draft", false, null, 2, 9, "Hi"))
        assertNull(InsertionRules.setTextSplice("Draft", false, null, 4, 2, "Hi"))
    }

    @Test
    fun onlyAProvablyUnchangedFieldMayTryAgain() {
        assertTrue(InsertionRules.unchanged("abc", "abc"))
        assertFalse(InsertionRules.unchanged("abc", "abc Hello"))
        assertFalse(InsertionRules.unchanged(null, "abc"))
        assertFalse(InsertionRules.unchanged("abc", null))
    }
}
