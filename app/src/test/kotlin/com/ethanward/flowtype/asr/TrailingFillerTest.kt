package com.ethanward.flowtype.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingFillerTest {
    private val ms = 16

    private fun piece(fromMs: Int, toMs: Int, text: String) = Piece(Span(fromMs * ms, toMs * ms), text)

    /** The recording from the phone: a sentence, then handling noise into the ✓ tap. */
    private val sentence = piece(1094, 2924, "See you at the shop at six.")

    @Test
    fun noiseRunningIntoTheTapIsDropped() {
        for (filler in listOf("Mm-hmm.", "Yeah.", "yeah", "Mm.", "Hmm?", "Uh-huh.", "Mhm.", "Mm hmm.")) {
            val pieces = listOf(sentence, piece(3374, 3930, filler))
            assertEquals(filler, listOf(sentence), dropTrailingFiller(pieces, total = 3930 * ms))
        }
    }

    @Test
    fun aFillerFollowedByAWaitIsKept() {
        val pieces = listOf(sentence, piece(3374, 3700, "Yeah."))
        assertEquals(pieces, dropTrailingFiller(pieces, total = 4500 * ms))
    }

    @Test
    fun realWordsAreKept() {
        for (text in listOf("Yeah, see you then.", "Yes.", "Okay.", "Thanks.")) {
            val pieces = listOf(sentence, piece(3374, 3930, text))
            assertEquals(text, pieces, dropTrailingFiller(pieces, total = 3930 * ms))
        }
    }

    @Test
    fun aFillerOnItsOwnIsKept() {
        val pieces = listOf(piece(0, 600, "Yeah."))
        assertEquals(pieces, dropTrailingFiller(pieces, total = 600 * ms))
    }

    @Test
    fun aPieceAfterAForcedCutIsKept() {
        val pieces = listOf(piece(0, 19_000, "We talked for a long time and then he said"), piece(19_000, 19_400, "yeah"))
        assertEquals(pieces, dropTrailingFiller(pieces, total = 19_400 * ms))
    }

    @Test
    fun onlyTheLastPieceIsLookedAt() {
        val pieces = listOf(piece(0, 800, "Yeah."), piece(1500, 3000, "That works for me."))
        assertEquals(pieces, dropTrailingFiller(pieces, total = 3000 * ms))
    }
}
