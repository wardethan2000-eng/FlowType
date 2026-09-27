package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.cleanup.Guards.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardsTest {
    @Test
    fun fillersDontCountAsWords() {
        assertEquals(listOf("so", "we", "meet", "at", "four"), Guards.contentWords("Um, so, uh, we meet at, you know, four"))
    }

    @Test
    fun ordinaryCleanupPasses() {
        assertEquals(Verdict.OK, Guards.check(
            "um so I was thinking we could uh meet at three no four o'clock tomorrow",
            "So I was thinking we could meet at 4 o'clock tomorrow.",
        ))
    }

    @Test
    fun anAnswerIsTooLong() {
        assertEquals(Verdict.TOO_LONG, Guards.length(
            "what is the capital of france",
            "The capital of France is Paris, which is also its largest city and a major European center.",
        ))
    }

    @Test
    fun shortInputsMayShrinkALot() {
        // Numbers and corrections collapse; under 8 words there's no lower bound.
        assertEquals(Verdict.OK, Guards.length("twenty five dollars no thirty five", "$35"))
    }

    @Test
    fun longInputsMayNotVanish() {
        assertEquals(Verdict.TOO_SHORT, Guards.length(
            "please send the invoice to the accounting team before the end of the day on friday",
            "Sent.",
        ))
    }

    @Test
    fun emptyAnswerToRealInput() {
        assertEquals(Verdict.EMPTY, Guards.length("call me when you land", ""))
        assertEquals(Verdict.OK, Guards.length("uh um", ""))
    }

    @Test
    fun assistantOpeningsAreCaught() {
        assertTrue(Guards.soundsLikeAssistant("write me a poem about the sea", "Sure! Here's a poem"))
        assertTrue(Guards.soundsLikeAssistant("what's the weather", "I'm sorry, I can't check"))
        assertTrue(Guards.soundsLikeAssistant("tell me a joke", "\"Here's one"))
        assertEquals(Verdict.ASSISTANT, Guards.check("tell me a joke about cats", "Certainly, here is a joke about cats."))
    }

    @Test
    fun butNotWhenTheSpeakerSaidIt() {
        assertFalse(Guards.soundsLikeAssistant("sure I can do friday", "Sure, I can do Friday."))
        assertFalse(Guards.soundsLikeAssistant("here's the address", "Here's the address"))
        // "Surely" isn't "Sure".
        assertFalse(Guards.soundsLikeAssistant("surely not", "Surely not."))
    }

    @Test
    fun judgesTheOpeningOnceThreeWordsAreIn() {
        assertFalse(Guards.canJudgeStart("Sure,", finished = false))
        assertTrue(Guards.canJudgeStart("Sure, here is", finished = false))
        assertTrue(Guards.canJudgeStart("Ok", finished = true))
    }

    @Test
    fun aClockTimeIsOneWord() {
        assertEquals(listOf("meet", "at", "10:00", "sarah"), Guards.words("Meet at 10:00. Sarah:"))
    }

    @Test
    fun aLostSnippetIsRejected() {
        assertEquals(Verdict.SNIPPETS, Guards.check("send it to ⟦S1⟧ by friday", "Send it to my address by Friday."))
        assertEquals(Verdict.OK, Guards.check("send it to ⟦S1⟧ by friday", "Send it to ⟦S1⟧ by Friday."))
    }
}
