package com.ethanward.flowtype.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundsLikeTest {
    private val words = listOf("Bambu", "DecalForge", "Ethan", "PETG", "Kate", "Siobhan")

    private fun run(text: String) = SoundsLike.apply(text, words).first

    @Test
    fun namesHeardAsOrdinaryWords() {
        assertEquals("Print it on the Bambu tonight.", run("Print it on the bamboo tonight."))
        assertEquals("Open DecalForge now", run("Open deck all forge now"))
        assertEquals("Ethan said so", run("Eathan said so"))
    }

    @Test
    fun shortWordsAndAcronymsAreNeverFuzzy() {
        // "Kate" has 4 letters: "cat" must stay a cat. PETG is read as letters.
        assertEquals("the cat sat", run("the cat sat"))
        assertEquals("pet gee", run("pet gee"))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        val text = "We met at the bank about the budget, and then went home for dinner."
        assertEquals(text, run(text))
        assertEquals("the family forged ahead", run("the family forged ahead"))
    }

    @Test
    fun exactSpellingsAreNotCounted() {
        assertEquals("Bambu" to 0, SoundsLike.apply("Bambu", words))
        assertEquals(1, SoundsLike.apply("bamboo", words).second)
    }

    @Test
    fun keys() {
        assertEquals(SoundsLike.key("Bambu"), SoundsLike.key("bamboo"))
        assertEquals(SoundsLike.key("DecalForge"), SoundsLike.key("deckallforge"))
        assertFalse(SoundsLike.key("Bambu") == SoundsLike.key("bank"))
        assertTrue(SoundsLike.matches("bamboo", "Bambu"))
        assertFalse(SoundsLike.matches("bamboozle", "Bambu"))
    }

    @Test
    fun onlyThroughTheSwitch() {
        val d = Dictionary().withWord("Bambu")
        assertEquals("the bamboo", DictionaryPass(d).apply("the bamboo").text)
        assertEquals("the Bambu", DictionaryPass(d, soundsLike = true).apply("the bamboo").text)
    }

    @Test
    fun shortNamesCatchDoubledLetters() {
        val names = listOf("Alan", "Kate")
        assertEquals("Alan said hi to Alan", SoundsLike.apply("Allan said hi to allan", names).first)
        // Different names, and joined words, stay as heard.
        assertEquals("Allen and Ellen", SoundsLike.apply("Allen and Ellen", names).first)
        assertEquals("all an hour", SoundsLike.apply("all an hour", names).first)
        assertEquals("the cat", SoundsLike.apply("the cat", names).first)
    }
}
