package com.ethanward.flowtype.cleanup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTitlerTest {
    @Test
    fun requestIsSmallAndUnstored() {
        val b = NoteTitler.body("Buy PETG and <b>glue</b>", CleanupConfig.LUNA)
        assertEquals("gpt-6-luna", b.getString("model"))
        assertEquals("none", b.getJSONObject("reasoning").getString("effort"))
        assertFalse(b.getBoolean("store"))
        assertEquals(24, b.getInt("max_output_tokens"))
        assertEquals("<note>Buy PETG and ‹b›glue‹/b›</note>", b.getJSONArray("input").getJSONObject(0).getString("content"))
    }

    @Test
    fun readsTheAnswerText() {
        val r = JSONObject("""{"output":[{"type":"message","content":[{"type":"output_text","text":"Filament shopping list"}]}]}""")
        assertEquals("Filament shopping list", NoteTitler.outputText(r))
    }

    @Test
    fun tidiesTitles() {
        assertEquals("Filament shopping list", NoteTitler.tidy("\"Filament shopping list.\""))
        assertEquals("Call Alan about the quote", NoteTitler.tidy("Title: Call Alan about the quote\n"))
        assertNull(NoteTitler.tidy(""))
        assertNull(NoteTitler.tidy("Sure, here is a title for your note about the thing you mentioned earlier"))
        assertNull(NoteTitler.tidy("Sure! Groceries"))
    }

    @Test
    fun fallbackIsTheFirstWords() {
        assertEquals("Remember to order more PETG for…", NoteTitler.fallback("Remember to order more PETG for the Bambu tomorrow."))
        assertEquals("Call Alan", NoteTitler.fallback("Call Alan."))
    }
}
