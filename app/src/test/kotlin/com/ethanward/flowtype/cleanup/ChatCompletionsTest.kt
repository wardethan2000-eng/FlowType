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

class ChatCompletionsTest {
    private val server = MockWebServer().apply { start() }
    private val provider = ChatCompletions(server.url("/v1/").toString())
    private val call = CleanupCall("test-key", CleanupConfig.DEFAULT.copy(model = "some-model"), "um see you at the cafe", listOf("DecalForge"), "messaging", null)

    @After
    fun stop() = server.shutdown()

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun delta(text: String) = """{"choices":[{"index":0,"delta":{"content":${JSONObject.quote(text)}},"finish_reason":null}]}"""

    @Test
    fun streamsTheAnswerAndTheUsage() {
        server.enqueue(
            sse(
                delta("See you"), delta(" at the café."),
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":1500,"completion_tokens":6,"prompt_tokens_details":{"cached_tokens":1280}}}""",
                "[DONE]",
            ),
        )
        val r = provider.stream(call, 5_000) { true } as Streamed.Done
        assertEquals("See you at the café.", r.text)
        assertEquals(Usage(1500, 1280, 6), r.usage)
        assertTrue(r.firstTokenMs >= 0)

        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer test-key", sent.getHeader("Authorization"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("some-model", body.getString("model"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").endsWith("DecalForge"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("<transcript>um see you at the cafe</transcript>"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun aLocalServerWithoutAKeyGetsNoAuthorization() {
        server.enqueue(sse(delta("Hi."), "[DONE]"))
        provider.stream(call.copy(key = ""), 5_000) { true }
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun aServerThatSendsNoUsageIsEstimated() {
        server.enqueue(sse(delta("See you there."), "[DONE]"))
        val r = provider.stream(call, 5_000) { true } as Streamed.Done
        assertEquals(true, r.usage?.estimated)
    }

    @Test
    fun stoppingEarlyCancelsTheRequest() {
        server.enqueue(sse(delta("Sure,"), delta(" here is"), delta(" a joke"), "[DONE]"))
        var calls = 0
        val r = provider.stream(call, 5_000) { calls++; calls < 2 }
        assertTrue(r is Streamed.Stopped)
        assertEquals(2, calls)
    }

    @Test
    fun anErrorInsideTheStreamFails() {
        server.enqueue(sse(delta("See"), """{"error":{"message":"overloaded"}}"""))
        val r = provider.stream(call, 5_000) { true } as Streamed.Failed
        assertEquals(Reason.HTTP_ERROR, r.reason)
    }

    @Test
    fun httpErrorsMapToReasons() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
        val r = provider.stream(call, 5_000) { true } as Streamed.Failed
        assertEquals(Reason.KEY_REJECTED, r.reason)
        assertEquals(401, r.http)
        assertNull(r.usage)
    }

    @Test
    fun aSlowServerRunsOutTheDeadline() {
        server.enqueue(sse(delta("See you"), "[DONE]").setHeadersDelay(2, TimeUnit.SECONDS))
        val r = provider.stream(call, 300) { true } as Streamed.Failed
        assertEquals(Reason.DEADLINE, r.reason)
    }

    @Test
    fun errorBodiesFromDifferentServers() {
        assertEquals(Reason.KEY_REJECTED, ChatCompletions.errorReason(401, ""))
        assertEquals(Reason.NO_CREDIT, ChatCompletions.errorReason(402, """{"error":{"message":"Insufficient credits"}}"""))
        assertEquals(Reason.NO_CREDIT, ChatCompletions.errorReason(429, """{"error":{"code":"insufficient_quota","message":"x"}}"""))
        assertEquals(Reason.RATE_LIMITED, ChatCompletions.errorReason(429, """{"error":{"message":"Rate limit reached"}}"""))
        assertEquals(Reason.MODEL_UNAVAILABLE, ChatCompletions.errorReason(404, "not json"))
        assertEquals(Reason.MODEL_UNAVAILABLE, ChatCompletions.errorReason(400, """{"error":{"message":"The model `nope` does not exist"}}"""))
        assertEquals(Reason.HTTP_ERROR, ChatCompletions.errorReason(400, """{"error":{"message":"bad temperature"}}"""))
        assertEquals(Reason.HTTP_ERROR, ChatCompletions.errorReason(500, ""))
    }

    @Test
    fun theStreamParserIgnoresKeepAlivesAndNulls() {
        val s = ChatStream()
        assertFalse(s.line(": keep-alive"))
        assertFalse(s.line(""))
        s.line("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}""")
        assertFalse(s.line(""))
        s.line("""data: {"choices":[{"delta":{"content":"Hi"}}]}""")
        assertTrue(s.line(""))
        assertEquals("Hi", s.text.toString())
        assertFalse(s.done)
    }
}
