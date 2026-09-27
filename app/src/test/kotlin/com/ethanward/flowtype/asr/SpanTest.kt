package com.ethanward.flowtype.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpanTest {
    @Test
    fun padsAndClamps() {
        assertEquals(Span(2000, 14000), Span(10000, 12000).padded(8000, 2000, 100_000))
        assertEquals(Span(0, 5000), Span(3000, 4000).padded(8000, 1000, 5000))
    }

    @Test
    fun joinsNonEmptySegments() {
        assertEquals("Hello there. How are you?", joinSegments(listOf(" Hello there. ", "", "How are you?")))
    }

    @Test
    fun cutsAtTheQuietestMomentOfAPause() {
        // Loud, then a pause with a soft word in its first half, then silence.
        val audio = FloatArray(16_000) { i ->
            when {
                i < 4000 -> 0.5f
                i in 6000 until 7000 -> 0.05f
                i >= 9000 -> 0f
                else -> 0.01f
            }
        }
        val at = quietestPoint(audio, 4000, 16_000)
        assertEquals(true, at >= 9000)
    }

    @Test
    fun aRangeShorterThanAWindowCutsAtItsEnd() {
        assertEquals(4100, quietestPoint(FloatArray(5000), 4000, 4100))
    }
}
