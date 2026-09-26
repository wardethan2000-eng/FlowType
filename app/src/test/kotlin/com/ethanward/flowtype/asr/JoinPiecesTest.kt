package com.ethanward.flowtype.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinPiecesTest {
    private val sec = 16_000

    /** Pieces [gapsMs] apart, each 2 s long. */
    private fun pieces(vararg texts: String, gapMs: Int = 550): List<Piece> {
        var at = 0
        return texts.map { t ->
            val p = Piece(Span(at, at + 2 * sec), t)
            at += 2 * sec + gapMs * 16
            p
        }
    }

    @Test
    fun shortPauseMidSentenceLosesTheFullStop() {
        assertEquals(
            "So I was thinking we could meet on Tuesday at noon.",
            joinPieces(pieces("So I was thinking.", "We could meet on Tuesday at noon.")),
        )
    }

    @Test
    fun longPauseKeepsTheSentences() {
        assertEquals(
            "That's done. Next, the invoice.",
            joinPieces(pieces("That's done.", "Next, the invoice.", gapMs = 1200)),
        )
    }

    @Test
    fun keepsTheCapitalOfIAcronymsAndDictionaryWords() {
        assertEquals("Then I left", joinPieces(pieces("Then.", "I left")))
        assertEquals("Print it in PETG", joinPieces(pieces("Print it in.", "PETG")))
        assertEquals("Open DecalForge now", joinPieces(pieces("Open.", "DecalForge now")))
        assertEquals("Ask Bambu support", joinPieces(pieces("Ask.", "Bambu support"), keepCase = setOf("Bambu")))
    }

    @Test
    fun questionsAndExclamationsStay() {
        assertEquals("Are you coming? We leave at six.", joinPieces(pieces("Are you coming?", "We leave at six.")))
    }

    @Test
    fun wordsHeardTwiceAtTheSeamAreDropped() {
        assertEquals("send it to the office today", joinPieces(pieces("send it to the", "the office today")))
        assertEquals("meet at the cafe", joinPieces(pieces("meet at the cafe", "the cafe")))
    }

    @Test
    fun emptyPiecesAreSkipped() {
        assertEquals("Hello there", joinPieces(pieces("Hello.", "there", "")))
        assertEquals("", joinPieces(emptyList()))
    }
}
