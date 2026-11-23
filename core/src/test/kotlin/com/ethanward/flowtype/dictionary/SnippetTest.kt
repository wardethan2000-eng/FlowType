package com.ethanward.flowtype.dictionary

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetTest {
    private val address = "12 Oak Lane\nSpringfield"
    private val dict = Dictionary()
        .withWord("DecalForge")
        .withSnippet(Snippet("My Address", address))
        .withSnippet(Snippet("my work address", "1 Main St"))

    @Test
    fun aTriggerBecomesATokenAndExpandsAtTheEnd() {
        val pass = DictionaryPass(dict)
        val marked = pass.apply("Send it to my address, please.").text
        assertEquals("Send it to ⟦S2⟧, please.", marked)
        assertEquals("Send it to $address, please.", pass.expand(marked))
    }

    @Test
    fun theLongerTriggerWins() {
        val pass = DictionaryPass(dict)
        assertEquals("Ship to 1 Main St.", pass.expand(pass.apply("Ship to my work address.").text))
    }

    @Test
    fun dictionaryPassesDontTouchTheSnippetText() {
        val d = Dictionary().withWord("Oak").withSnippet(Snippet("sig", "oak and decal forge"))
        val pass = DictionaryPass(d)
        assertEquals("oak and decal forge", pass.expand(pass.apply("sig").text))
    }

    @Test
    fun tokensMustSurviveCleanupExactly() {
        assertTrue(SnippetTokens.same("to ⟦S1⟧ and ⟦S2⟧", "To ⟦S2⟧ and ⟦S1⟧."))
        assertFalse(SnippetTokens.same("to ⟦S1⟧", "To my address."))
        assertFalse(SnippetTokens.same("to ⟦S1⟧", "To ⟦S1⟧ ⟦S1⟧."))
        assertEquals("Send it to .", SnippetTokens.strip("Send it to ⟦S1⟧."))
    }

    @Test
    fun snippetsRoundTripThroughTheFile() {
        val back = Dictionary.fromJson(JSONObject(dict.toJson().toString()))
        assertEquals(dict.snippets, back.snippets)
        assertEquals(listOf("my address", "my work address"), back.snippets.map { it.trigger })
    }

    @Test
    fun snippetProblems() {
        assertNotNull(Dictionary.problemWithSnippet("a", "text"))
        assertNotNull(Dictionary.problemWithSnippet("my address", "  "))
        assertNull(Dictionary.problemWithSnippet("my address", address))
    }
}
