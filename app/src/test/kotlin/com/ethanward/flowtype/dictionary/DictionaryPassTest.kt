package com.ethanward.flowtype.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryPassTest {
    private val dict = Dictionary()
        .withWord("DecalForge")
        .withWord("PETG")
        .withWord("Ethan")
        .withWord("iPhone")
        .withReplacement(Replacement("pet g", "PETG"))
        .withReplacement(Replacement("bamboo", "Bambu"))
        .withReplacement(Replacement("gonna", "going to"))

    private fun run(text: String) = DictionaryPass(dict).apply(text).text

    @Test
    fun replacesPhrasesAnyCaseAndSpacing() {
        assertEquals("Print it in PETG on the Bambu.", run("Print it in pet g on the bamboo."))
        assertEquals("PETG is fine.", run("Pet-G is fine."))
        assertEquals("PETG, then", run("pet  g, then"))
    }

    @Test
    fun onlyWholeWords() {
        assertEquals("bamboozled and carpet green", run("bamboozled and carpet green"))
        assertEquals("the carpets", DictionaryPass(Dictionary().withWord("Carpet")).apply("the carpets").text)
    }

    @Test
    fun replacementOutputsWithCapitalsAreSpellingsToo() {
        // "petg" isn't the spoken form "pet g", but PETG is a spelling to keep.
        assertEquals("PETG", DictionaryPass(Dictionary().withReplacement(Replacement("pet g", "PETG"))).apply("petg").text)
    }

    @Test
    fun respellsWordsAndJoinsCamelCaseHeardApart() {
        assertEquals("The DecalForge app", run("The Decalforge app"))
        assertEquals("The DecalForge app", run("The decal forge app"))
        assertEquals("Open DecalForge.", run("Open Decal-Forge."))
        assertEquals("my iPhone", run("my I phone"))
        assertEquals("Ethan said", run("ethan said"))
    }

    @Test
    fun lowercaseReplacementTakesTheSentenceCapital() {
        assertEquals("Going to print it, going to ship it.", run("Gonna print it, gonna ship it."))
    }

    @Test
    fun replacementOutputIsNotReplacedAgain() {
        val d = Dictionary()
            .withReplacement(Replacement("a b", "c d"))
            .withReplacement(Replacement("c d", "e"))
        assertEquals("c d", DictionaryPass(d).apply("a b").text)
    }

    @Test
    fun longestPhraseWins() {
        val d = Dictionary()
            .withReplacement(Replacement("new york", "NY"))
            .withReplacement(Replacement("new york city", "NYC"))
        assertEquals("NYC and NY", DictionaryPass(d).apply("new york city and new york").text)
    }

    @Test
    fun countsWhatItChanged() {
        val r = DictionaryPass(dict).apply("the decal forge sign in pet g, PETG, DecalForge")
        assertEquals("the DecalForge sign in PETG, PETG, DecalForge", r.text)
        assertEquals(1, r.replaced)
        assertEquals(1, r.respelled) // "decal forge"; already-right spellings aren't counted
    }

    @Test
    fun emptyDictionaryChangesNothing() {
        val r = DictionaryPass(Dictionary()).apply("Hello there.")
        assertEquals(DictionaryPass.Result("Hello there.", 0, 0), r)
    }

    @Test
    fun regexCharactersInEntriesAreLiteral() {
        val d = Dictionary().withWord("C++").withReplacement(Replacement("dot net", ".NET"))
        assertEquals("C++ and .NET", DictionaryPass(d).apply("c++ and dot net").text)
    }

    @Test
    fun camelParts() {
        assertEquals(listOf("Decal", "Forge"), DictionaryPass.camelParts("DecalForge"))
        assertEquals(listOf("PETG"), DictionaryPass.camelParts("PETG"))
        assertEquals(listOf("i", "Phone"), DictionaryPass.camelParts("iPhone"))
        assertEquals(listOf("New York"), DictionaryPass.camelParts("New York"))
    }
}
