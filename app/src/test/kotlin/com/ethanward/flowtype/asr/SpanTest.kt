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
}
