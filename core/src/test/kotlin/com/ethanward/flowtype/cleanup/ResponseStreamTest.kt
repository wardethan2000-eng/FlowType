package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseStreamTest {
    private fun feed(s: ResponseStream, vararg events: String): List<Boolean> =
        events.flatMap { e -> listOf("event: x", "data: $e", "").map { s.line(it) } }

    @Test
    fun collectsTextAndFlagsTheFirstDeltaOnce() {
        val s = ResponseStream()
        val firsts = feed(
            s,
            """{"type":"response.created","response":{}}""",
            """{"type":"response.output_text.delta","delta":"Hello"}""",
            """{"type":"response.output_text.delta","delta":" there."}""",
        )
        assertEquals(1, firsts.count { it })
        assertEquals("Hello there.", s.text.toString())
        assertFalse(s.done)
    }

    @Test
    fun readsUsageAndCachedTokens() {
        val s = ResponseStream()
        feed(
            s,
            """{"type":"response.completed","response":{"status":"completed","service_tier":"fast","usage":{"input_tokens":1210,"input_tokens_details":{"cached_tokens":1152},"output_tokens":18,"output_tokens_details":{"reasoning_tokens":0}}}}""",
        )
        assertTrue(s.done)
        assertEquals("completed", s.status)
        assertEquals("fast", s.serviceTier)
        assertEquals(1210, s.inputTokens)
        assertEquals(1152, s.cachedTokens)
        assertEquals(18, s.outputTokens)
        assertEquals(0, s.reasoningTokens)
    }

    @Test
    fun errorsEndTheStream() {
        val s = ResponseStream()
        feed(s, """{"type":"error","message":"model not found"}""")
        assertTrue(s.done)
        assertEquals("model not found", s.error)
    }
}
