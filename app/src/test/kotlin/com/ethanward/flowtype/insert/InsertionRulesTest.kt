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
}
