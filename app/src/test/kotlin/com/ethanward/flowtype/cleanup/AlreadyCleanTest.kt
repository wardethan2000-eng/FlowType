package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlreadyCleanTest {
    @Test
    fun plainShortSentencesSkipCleanup() {
        assertTrue(AlreadyClean.check("Sounds good, see you there.", AppStyle.MESSAGING))
        assertTrue(AlreadyClean.check("Are you free for lunch tomorrow?", AppStyle.GENERAL))
        assertTrue(AlreadyClean.check("I'll send it over tonight.", AppStyle.EMAIL))
    }

    @Test
    fun longerDictationsStillGetCleanup() {
        assertFalse(AlreadyClean.check("I think we should head over there after the game ends.", AppStyle.MESSAGING))
    }

    @Test
    fun anythingTheModelWouldChangeGoesToIt() {
        assertFalse(AlreadyClean.check("Um, see you there.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("Tuesday, actually Wednesday.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("Meet at four thirty.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("It's 25 bucks.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("The the order shipped.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("It was, you know, fine.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("Email me at john dot com.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("Eggs bullet milk.", AppStyle.NOTES))
    }

    @Test
    fun aStrayYeahOrOkayBesideASentenceGoesToTheModel() {
        // History, 2026-09-29: "Okay." on the end of a question skipped cleanup.
        assertFalse(AlreadyClean.check("Would that work for you on Friday? Okay.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("Yeah. I'm leaving now.", AppStyle.MESSAGING))
        assertFalse(AlreadyClean.check("Mm-hmm, see you there.", AppStyle.MESSAGING))
        // Said as part of the sentence: typed as it is.
        assertTrue(AlreadyClean.check("Okay, see you there.", AppStyle.MESSAGING))
        assertTrue(AlreadyClean.check("Oh well, we tried.", AppStyle.MESSAGING))
    }

    @Test
    fun searchAlwaysGetsCleanup() {
        assertFalse(AlreadyClean.check("Pharmacy hours Sunday.", AppStyle.SEARCH))
    }

    @Test
    fun cueWordsInsideOtherWordsDontCount() {
        // "none" and "someone" contain "no" and "one"; "likely" contains "like".
        assertTrue(AlreadyClean.check("Someone likely has none left.", AppStyle.GENERAL))
    }

    @Test
    fun messagingDropsTheFinalPeriodOfOneSentence() {
        assertEquals("Sounds good", AlreadyClean.finish("Sounds good.", AppStyle.MESSAGING))
        assertEquals("On my way. See you soon.", AlreadyClean.finish("On my way. See you soon.", AppStyle.MESSAGING))
        assertEquals("Really?", AlreadyClean.finish("Really?", AppStyle.MESSAGING))
        assertEquals("Wait...", AlreadyClean.finish("Wait...", AppStyle.MESSAGING))
        assertEquals("Sounds good.", AlreadyClean.finish("Sounds good.", AppStyle.EMAIL))
    }
}
