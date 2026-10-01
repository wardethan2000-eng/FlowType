package com.ethanward.flowtype.cleanup

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelListTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.shutdown()

    @Test
    fun anOpenAiCompatibleServerListsItsModels() {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"b-model"},{"id":"a-model"},{"id":"a-model"}]}"""))
        val answer = ModelList.fetch(Providers.GROQ, server.url("/openai/v1").toString(), "gsk_x")
        assertEquals(ModelList.Answer.Models(listOf("a-model", "b-model")), answer)
        val sent = server.takeRequest()
        assertEquals("/openai/v1/models", sent.path)
        assertEquals("Bearer gsk_x", sent.getHeader("Authorization"))
    }

    @Test
    fun anthropicListsWithItsOwnHeaders() {
        server.enqueue(MockResponse().setBody("""{"data":[{"type":"model","id":"claude-haiku-4-5"}],"has_more":false}"""))
        val answer = ModelList.fetch(Providers.ANTHROPIC, server.url("/").toString(), "sk-ant-x")
        assertEquals(ModelList.Answer.Models(listOf("claude-haiku-4-5")), answer)
        val sent = server.takeRequest()
        assertEquals("/v1/models?limit=1000", sent.path)
        assertEquals("sk-ant-x", sent.getHeader("x-api-key"))
    }

    @Test
    fun aLocalServerGetsNoKeyAndABadKeySaysSo() {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        ModelList.fetch(Providers.CUSTOM, server.url("/v1").toString(), "")
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        assertEquals(ModelList.Answer.Failed(Cleaner.Reason.KEY_REJECTED, 401), ModelList.fetch(Providers.GROQ, server.url("/v1").toString(), "gsk_bad"))
    }
}
