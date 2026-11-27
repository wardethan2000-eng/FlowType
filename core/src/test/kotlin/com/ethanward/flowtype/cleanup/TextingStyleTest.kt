package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class TextingStyleTest {
    private val two = "Running late. Be there at five."

    @Test
    fun theDefaultDropsOnlyTheLastPeriod() {
        assertEquals(TextingStyle.NO_LAST_PERIOD, TextingStyle.DEFAULT)
        assertEquals(TextingStyle.DEFAULT, TextingStyle.byId(null))
        assertEquals(TextingStyle.DEFAULT, TextingStyle.byId("gone"))
        assertEquals(TextingStyle.LINES, TextingStyle.byId("lines"))
    }

    @Test
    fun theExamplesOnTheSettingsScreenAreWhatYouGet() {
        for (style in TextingStyle.entries) assertEquals(style.example, style.apply(two))
    }

    @Test
    fun fullPunctuationDropsThePeriodOfOneSentenceOnly() {
        val s = TextingStyle.ONE_SENTENCE
        assertEquals("Sounds good", s.apply("Sounds good."))
        assertEquals("On my way. See you soon.", s.apply("On my way. See you soon."))
        assertEquals("Shopping list\n- Eggs.", s.apply("Shopping list\n- Eggs."))
        assertEquals("Really?", s.apply("Really?"))
        assertEquals("Wait...", s.apply("Wait..."))
    }

    @Test
    fun noLastPeriodKeepsEveryOtherMark() {
        val s = TextingStyle.NO_LAST_PERIOD
        assertEquals("Sounds good", s.apply("Sounds good."))
        assertEquals("Are you coming? I'm leaving now", s.apply("Are you coming? I'm leaving now."))
        assertEquals("I'm leaving now. Are you coming?", s.apply("I'm leaving now. Are you coming?"))
        assertEquals("That's great!", s.apply("That's great!"))
        assertEquals("Well...", s.apply("Well..."))
        assertEquals("It costs $3.50", s.apply("It costs $3.50."))
        assertEquals("lol yeah that's hilarious", s.apply("lol yeah that's hilarious"))
        assertEquals("", s.apply(""))
    }

    @Test
    fun anAbbreviationKeepsItsPeriod() {
        assertEquals("Bring chips, salsa, etc.", TextingStyle.NO_LAST_PERIOD.apply("Bring chips, salsa, etc."))
        assertEquals("Dr. Smith is here", TextingStyle.ONE_SENTENCE.apply("Dr. Smith is here."))
        assertEquals("Dr. Smith is here\nHe wants to see you", TextingStyle.LINES.apply("Dr. Smith is here. He wants to see you."))
        assertEquals("Ask J. Smith about it", TextingStyle.LINES.apply("Ask J. Smith about it."))
    }

    @Test
    fun linesPutsEachSentenceOnItsOwnLine() {
        val s = TextingStyle.LINES
        assertEquals("Are you coming?\nI'm leaving now\nHurry!", s.apply("Are you coming? I'm leaving now. Hurry!"))
        assertEquals("Sounds good", s.apply("Sounds good."))
        // Lines the speaker asked for stay lines.
        assertEquals("Shopping list\n- Eggs\n- Milk", s.apply("Shopping list\n- Eggs\n- Milk"))
        assertEquals("It costs $3.50\nI'll pay", s.apply("It costs $3.50. I'll pay."))
    }

    @Test
    fun casualLowercasesEverydayWordsButNotNames() {
        val s = TextingStyle.CASUAL
        assertEquals("sounds good", s.apply("Sounds good."))
        assertEquals("that's fine, let's go", s.apply("That's fine. Let's go."))
        assertEquals("I'm on my way, I'll be there soon", s.apply("I'm on my way. I'll be there soon."))
        assertEquals("Mike is running late, Sarah will be there", s.apply("Mike is running late. Sarah will be there."))
        assertEquals("are you coming? we're leaving now", s.apply("Are you coming? We're leaving now."))
        assertEquals("the DecalForge sign is done, NASA called", s.apply("The DecalForge sign is done. NASA called."))
    }

    @Test
    fun aSnippetMarkerIsLeftAlone() {
        assertEquals("send it to ⟦S1⟧", TextingStyle.CASUAL.apply("Send it to ⟦S1⟧."))
        assertEquals("⟦S1⟧", TextingStyle.NO_LAST_PERIOD.apply("⟦S1⟧"))
    }
}
