package com.ethanward.flowtype.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectionsTest {
    private val typed = "I printed it on the bamboo today."

    @Test
    fun oneReplacedWordIsFound() {
        assertEquals("Bambu", Corrections.find(typed, "Hi. I printed it on the Bambu today. More", emptySet()))
    }

    @Test
    fun aMidSentenceCapitalCounts() {
        assertEquals("Petg", Corrections.find("it came out in petg fine", "it came out in Petg fine", emptySet()))
    }

    @Test
    fun aSentenceStartCapitalDoesnt() {
        assertNull(Corrections.find("okay see you there", "Okay see you there", emptySet()))
        assertNull(Corrections.find("Done. okay see you", "Done. Okay see you", emptySet()))
    }

    @Test
    fun untouchedOrReworkedTextOffersNothing() {
        assertNull(Corrections.find(typed, typed, emptySet()))
        assertNull(Corrections.find(typed, "I printed it on the Bambu yesterday.", emptySet()))
        assertNull(Corrections.find(typed, "Something else entirely", emptySet()))
    }

    @Test
    fun punctuationAloneIsntACorrection() {
        assertNull(Corrections.find(typed, "I printed it on the bamboo today!", emptySet()))
    }

    @Test
    fun knownWordsAndShortDictationsAreSkipped() {
        assertNull(Corrections.find(typed, "I printed it on the Bambu today.", setOf("Bambu")))
        assertNull(Corrections.find("the bamboo", "the Bambu", emptySet()))
    }

    @Test
    fun anOrdinaryLowercaseWordIsntOffered() {
        assertNull(Corrections.find("I printed it on the Bambu today.", "I printed it on the bamboo today.", emptySet()))
    }

    @Test
    fun checkSaysWhyNothingWasOffered() {
        assertEquals("offered", Corrections.check(typed, "I printed it on the Bambu today.", emptySet()).why)
        assertEquals("unchanged", Corrections.check(typed, typed, emptySet()).why)
        assertEquals("lowercase", Corrections.check(typed, "I printed it on the bamboos today.", emptySet()).why)
        assertEquals("known", Corrections.check(typed, "I printed it on the Bambu today.", setOf("Bambu")).why)
        assertEquals("short_dictation", Corrections.check("the bamboo", "the Bambu", emptySet()).why)
        assertEquals("fewer_words", Corrections.check(typed, "printed", emptySet()).why)
        assertEquals("no_single_change", Corrections.check(typed, "I printed it on the Bambu yesterday.", emptySet()).why)
    }
}
