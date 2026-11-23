package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlreadyCleanTest {
    @Test
    fun plainShortSentencesSkipCleanup() {
        assertTrue(AlreadyClean.check("Sounds good, see you there.", Styles.MESSAGING))
        assertTrue(AlreadyClean.check("Are you free for lunch tomorrow?", Styles.GENERAL))
        assertTrue(AlreadyClean.check("I'll send it over tonight.", Styles.EMAIL))
    }

    @Test
    fun longerDictationsStillGetCleanup() {
        assertFalse(AlreadyClean.check("I think we should head over there after the game ends.", Styles.MESSAGING))
    }

    @Test
    fun anythingTheModelWouldChangeGoesToIt() {
        assertFalse(AlreadyClean.check("Um, see you there.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("Tuesday, actually Wednesday.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("Meet at four thirty.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("It's 25 bucks.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("The the order shipped.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("It was, you know, fine.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("Email me at john dot com.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("Eggs bullet milk.", Styles.NOTES))
    }

    @Test
    fun aStrayYeahOrOkayBesideASentenceGoesToTheModel() {
        // History, 2026-09-29: "Okay." on the end of a question skipped cleanup.
        assertFalse(AlreadyClean.check("Would that work for you on Friday? Okay.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("Yeah. I'm leaving now.", Styles.MESSAGING))
        assertFalse(AlreadyClean.check("Mm-hmm, see you there.", Styles.MESSAGING))
        // Said as part of the sentence: typed as it is.
        assertTrue(AlreadyClean.check("Okay, see you there.", Styles.MESSAGING))
        assertTrue(AlreadyClean.check("Oh well, we tried.", Styles.MESSAGING))
    }

    @Test
    fun searchAlwaysGetsCleanup() {
        assertFalse(AlreadyClean.check("Pharmacy hours Sunday.", Styles.SEARCH))
    }

    @Test
    fun cueWordsInsideOtherWordsDontCount() {
        // "none" and "someone" contain "no" and "one"; "likely" contains "like".
        assertTrue(AlreadyClean.check("Someone likely has none left.", Styles.GENERAL))
    }

    @Test
    fun messagingDropsTheFinalPeriodOfOneSentence() {
        assertEquals("Sounds good", AlreadyClean.finish("Sounds good.", Styles.MESSAGING))
        assertEquals("On my way. See you soon.", AlreadyClean.finish("On my way. See you soon.", Styles.MESSAGING))
        assertEquals("Really?", AlreadyClean.finish("Really?", Styles.MESSAGING))
        assertEquals("Wait...", AlreadyClean.finish("Wait...", Styles.MESSAGING))
        assertEquals("Sounds good.", AlreadyClean.finish("Sounds good.", Styles.EMAIL))
    }
}
