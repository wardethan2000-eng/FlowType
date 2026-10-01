package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.cleanup.Cleaner.Reason
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnthropicMessagesTest {
    private val server = MockWebServer().apply { start() }
    private val provider = AnthropicMessages(server.url("/").toString())
    private val haiku = CleanupCall("test-key", CleanupConfig.DEFAULT.copy(model = "claude-haiku-4-5"), "um see you at the cafe", listOf("DecalForge"), "messaging", "We met on")

    @After
    fun stop() = server.shutdown()

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    private fun start(input: Int = 20, read: Int = 0, write: Int = 0) =
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"usage":{"input_tokens":$input,"cache_read_input_tokens":$read,"cache_creation_input_tokens":$write,"output_tokens":1}}}"""

    private fun text(t: String) = "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${JSONObject.quote(t)}}}"""

    private fun end(stop: String = "end_turn", output: Int = 7) = arrayOf(
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":$output}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun streamsTheAnswerWithCacheUsage() {
        server.enqueue(sse(start(20, 1480, 0), "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""", text("See you"), text(" at the café."), *end()))
        val r = provider.stream(haiku, 5_000) { true } as Streamed.Done
        assertEquals("See you at the café.", r.text)
        assertEquals(Usage(1500, 1480, 7), r.usage)
        assertEquals(1480, r.cachedTokens)

        val sent = server.takeRequest()
        assertEquals("/v1/messages", sent.path)
        assertEquals("test-key", sent.getHeader("x-api-key"))
        assertEquals(AnthropicMessages.VERSION, sent.getHeader("anthropic-version"))
        val body = JSONObject(sent.body.readUtf8())
        val system = body.getJSONArray("system").getJSONObject(0)
        assertTrue(system.getString("text").endsWith("DecalForge"))
        assertEquals("1h", system.getJSONObject("cache_control").getString("ttl"))
        val user = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(user.contains("<before_cursor>We met on</before_cursor>"))
        assertTrue(user.contains("<transcript>um see you at the cafe</transcript>"))
    }

    @Test
    fun eachModelGetsTheParametersItAccepts() {
        val h = AnthropicMessages.body(haiku)
        assertEquals(0, h.getInt("temperature"))
        assertFalse(h.has("thinking"))
        assertFalse(h.has("output_config"))

        val s = AnthropicMessages.body(haiku.copy(config = haiku.config.copy(model = "claude-sonnet-5-5")))
        assertFalse(s.has("temperature"))
        assertEquals("between_tools", s.getJSONObject("thinking").getString("type"))
        assertEquals("low", s.getJSONObject("output_config").getString("effort"))

        val other = AnthropicMessages.body(haiku.copy(config = haiku.config.copy(model = "claude-opus-5-5")))
        assertFalse(other.has("temperature"))
        assertFalse(other.has("thinking"))
        assertEquals("low", other.getJSONObject("output_config").getString("effort"))
        assertTrue(other.getInt("max_tokens") >= 2_048)
    }

    @Test
    fun thinkingDeltasAreNotText() {
        server.enqueue(
            sse(
                start(),
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
                text("Hi."),
                *end(),
            ),
        )
        assertEquals("Hi.", (provider.stream(haiku, 5_000) { true } as Streamed.Done).text)
    }

    @Test
    fun aDeclineOrAnErrorEventFails() {
        server.enqueue(sse(start(), text("I can't"), *end(stop = "refusal")))
        assertEquals(Reason.HTTP_ERROR, (provider.stream(haiku, 5_000) { true } as Streamed.Failed).reason)
        server.enqueue(sse(start(), "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        assertEquals(Reason.HTTP_ERROR, (provider.stream(haiku, 5_000) { true } as Streamed.Failed).reason)
    }

    @Test
    fun stoppingEarlyCancelsTheRequest() {
        server.enqueue(sse(start(), text("Sure,"), text(" here"), text(" you go"), *end()))
        var calls = 0
        assertTrue(provider.stream(haiku, 5_000) { calls++; calls < 2 } is Streamed.Stopped)
        assertEquals(2, calls)
    }

    @Test
    fun httpErrorsMapToReasons() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val r = provider.stream(haiku, 5_000) { true } as Streamed.Failed
        assertEquals(Reason.KEY_REJECTED, r.reason)
        assertNull(r.usage)
        assertEquals(Reason.NO_CREDIT, AnthropicMessages.errorReason(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""))
        assertEquals(Reason.MODEL_UNAVAILABLE, AnthropicMessages.errorReason(404, """{"type":"error","error":{"type":"not_found_error","message":"model: nope"}}"""))
        assertEquals(Reason.RATE_LIMITED, AnthropicMessages.errorReason(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""))
        assertEquals(Reason.HTTP_ERROR, AnthropicMessages.errorReason(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
    }

    @Test
    fun aSlowServerRunsOutTheDeadline() {
        server.enqueue(sse(start(), text("Hi."), *end()).setHeadersDelay(2, TimeUnit.SECONDS))
        assertEquals(Reason.DEADLINE, (provider.stream(haiku, 300) { true } as Streamed.Failed).reason)
    }
}
