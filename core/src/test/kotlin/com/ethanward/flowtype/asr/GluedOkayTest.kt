package com.ethanward.flowtype.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class GluedOkayTest {
    @Test
    fun anOkayRunOntoTheLastWordIsDropped() {
        assertEquals("On my way.", dropGluedOkay("On my way okay."))
        assertEquals("Miss you too.", dropGluedOkay("Miss you too okay."))
        assertEquals("Running late", dropGluedOkay("Running late okay"))
        assertEquals("Got it, see you soon.", dropGluedOkay("Got it, see you soon OK."))
        assertEquals("The car is fine. Just a scratch.", dropGluedOkay("The car is fine. Just a scratch okay."))
    }

    @Test
    fun anOkayTheSentenceNeedsIsKept() {
        for (text in listOf(
            "That's okay.", "That is okay.", "It's okay", "Are you okay?", "Is the dog okay?",
            "Hope you're okay.", "I'm okay.", "Everything okay.", "Sounds okay.", "Is that okay.",
            "See you then, okay.", "Okay.", "Okay okay.", "Call me back okay?", "Just okay.",
        )) {
            assertEquals(text, text, dropGluedOkay(text))
        }
    }

    @Test
    fun okayInsideTheTextIsLeftAlone() {
        assertEquals("Okay, see you there.", dropGluedOkay("Okay, see you there."))
        assertEquals("Okay so the plan works.", dropGluedOkay("Okay so the plan works."))
    }
}
