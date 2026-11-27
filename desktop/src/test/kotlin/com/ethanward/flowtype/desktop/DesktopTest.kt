package com.ethanward.flowtype.desktop

import com.ethanward.flowtype.dictionary.Dictionary
import com.ethanward.flowtype.dictionary.Replacement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTest {
    private val dict = Dictionary()
        .withWord("DecalForge")
        .withReplacement(Replacement("bamboo", "Bambu"))

    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.shutdown()

    private fun sse(vararg contents: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            contents.joinToString("") { c -> "data: " + JSONObject().put("choices", listOf(mapOf("delta" to mapOf("content" to c)))) + "\n\n" } +
                "data: [DONE]\n\n",
        )

    /** "Your own server" at the mock, which needs no key. */
    private fun custom(deadlineMs: Long = 3000) =
        Settings(provider = "custom", model = "test-model", server = server.url("/v1").toString(), deadlineMs = deadlineMs)

    private val longDictation = "um so I think we should uh print the decal forge sign on the bamboo tomorrow morning"

    @Test
    fun defaultsMatchThePhone() {
        val s = Settings.parse(JSONObject())
        assertEquals("anthropic", s.provider)
        assertNull(s.model)
        assertEquals(3000, s.deadlineMs)
        assertTrue(s.soundsLike)
        assertTrue(s.cleanup)
    }

    @Test
    fun settingsAreRead() {
        val s = Settings.parse(JSONObject("""{"provider":"groq","model":"m","deadlineMs":2500,"cleanup":false,"style":"email"}"""))
        assertEquals(Settings(provider = "groq", model = "m", deadlineMs = 2500, cleanup = false, style = "email"), s)
    }

    @Test
    fun withoutAKeyTheLocalPassIsTyped() {
        val out = Desktop(Settings(), dict, { null }).run("Print it on the bamboo. New line. Thanks okay.")
        assertEquals("Print it on the Bambu.\nThanks.", out)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cleanedTextGetsTheDictionaryAgain() {
        // The model wrote "Decalforge"; the second pass puts the saved spelling back.
        server.enqueue(sse("I think we should print the Decalforge sign on the Bambu tomorrow morning."))
        val out = Desktop(custom(), dict, { null }).run(longDictation)
        assertEquals("I think we should print the DecalForge sign on the Bambu tomorrow morning.", out)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("test-model"))
        assertTrue("the request carries the local pass's text", body.contains("DecalForge sign on the Bambu"))
    }

    @Test
    fun aFailedCallTypesTheLocalText() {
        server.enqueue(MockResponse().setResponseCode(500))
        val out = Desktop(custom(), dict, { null }).run(longDictation)
        assertEquals("um so I think we should uh print the DecalForge sign on the Bambu tomorrow morning", out)
    }

    @Test
    fun shortDictationsSkipTheCall() {
        var keyLookups = 0
        assertEquals("On my way.", Desktop(custom(), dict, { keyLookups++; null }).run("On my way."))
        assertEquals("I'll send it over tonight.", Desktop(custom(), dict, { keyLookups++; null }).run("I'll send it over tonight."))
        assertEquals(0, keyLookups)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyProblemsNotifyTheDesktop() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_api_key"}}"""))
        val notices = ArrayList<Pair<String, String>>()
        val d = Desktop(custom(), dict, { "bad-key" }) { title, body -> notices += title to body }
        val out = d.run(longDictation)
        assertEquals("um so I think we should uh print the DecalForge sign on the Bambu tomorrow morning", out)
        assertEquals("KEY_REJECTED", d.outcome)
        assertEquals(1, notices.size)
        assertTrue(notices[0].second.contains("didn't accept your key"))
    }

    @Test
    fun nonKeyFailuresDoNotNotify() {
        server.enqueue(MockResponse().setResponseCode(500))
        val notices = ArrayList<Pair<String, String>>()
        val d = Desktop(custom(), dict, { "k" }) { title, body -> notices += title to body }
        d.run(longDictation)
        assertEquals(0, notices.size)
    }

    @Test
    fun messagingStyleAppliesTextingPunctuation() {
        val s = custom().copy(style = "messaging", textingStyle = "no_last_period")
        assertEquals("Running late. See you soon", Desktop(s, dict, { null }).run("Running late. See you soon."))
        assertEquals(0, server.requestCount)
        val noAi = Settings(style = "messaging", textingStyle = "no_last_period", cleanup = false)
        assertEquals("Running late. Be there at five", Desktop(noAi, dict, { null }).run("Running late. Be there at five."))
    }
}
