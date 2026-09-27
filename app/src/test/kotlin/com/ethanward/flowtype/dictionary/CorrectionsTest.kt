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
}
