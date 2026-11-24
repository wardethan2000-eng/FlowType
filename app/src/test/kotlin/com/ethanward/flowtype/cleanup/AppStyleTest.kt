package com.ethanward.flowtype.cleanup

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppStyleTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun appsPickTheirStyle() {
        assertEquals(AppStyle.MESSAGING, AppStyle.forField("com.google.android.apps.messaging", text, 0))
        assertEquals(AppStyle.EMAIL, AppStyle.forField("com.google.android.gm", text, 0))
        assertEquals(AppStyle.NOTES, AppStyle.forField("com.google.android.keep", text, 0))
        assertEquals(AppStyle.GENERAL, AppStyle.forField("com.example.anything", text, 0))
    }

    @Test
    fun searchBoxesAreSearch() {
        assertEquals(AppStyle.SEARCH, AppStyle.forField("com.android.chrome", text, EditorInfo.IME_ACTION_SEARCH))
    }

    @Test
    fun addressAndNumberFieldsGetNoCleanup() {
        assertNull(AppStyle.forField("com.android.chrome", text or InputType.TYPE_TEXT_VARIATION_URI, 0))
        assertNull(AppStyle.forField("x", text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0))
        assertNull(AppStyle.forField("x", InputType.TYPE_CLASS_NUMBER, 0))
        assertNull(AppStyle.forField("x", InputType.TYPE_CLASS_PHONE, 0))
    }

    @Test
    fun theUsersPickForAnAppWins() {
        val picked = mapOf("com.example.chat" to AppStyle.MESSAGING, "com.google.android.gm" to AppStyle.GENERAL)
        assertEquals(AppStyle.MESSAGING, AppStyle.forField("com.example.chat", text, 0, picked))
        assertEquals(AppStyle.GENERAL, AppStyle.forField("com.google.android.gm", text, 0, picked))
        // Apps left alone keep Flowtype's own choice.
        assertEquals(AppStyle.NOTES, AppStyle.forField("com.google.android.keep", text, 0, picked))
    }

    @Test
    fun theFieldStillComesBeforeThePick() {
        val picked = mapOf("com.example.chat" to AppStyle.MESSAGING)
        assertNull(AppStyle.forField("com.example.chat", text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, picked))
        assertEquals(AppStyle.SEARCH, AppStyle.forField("com.example.chat", text, EditorInfo.IME_ACTION_SEARCH, picked))
    }

    @Test
    fun aPickThatIsNotAStyleIsIgnored() {
        assertEquals(AppStyle.GENERAL, AppStyle.forField("com.example.chat", text, 0, mapOf("com.example.chat" to "nonsense")))
        assertEquals(AppStyle.GENERAL, AppStyle.forField("com.example.chat", text, 0, mapOf("com.example.chat" to AppStyle.SEARCH)))
    }

    @Test
    fun emailSubjectsAreEmailInAnAppWithNoStyle() {
        assertEquals(AppStyle.EMAIL, AppStyle.forField("com.example.mail", text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 0))
    }
}
