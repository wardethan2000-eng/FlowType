package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.cleanup.Cleaner.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanerTest {
    @Test
    fun errorResponsesMapToWhatTheUserCanFix() {
        assertEquals(Reason.KEY_REJECTED, OpenAiResponses.errorReason(401, """{"error":{"code":"invalid_api_key"}}"""))
        assertEquals(Reason.NO_CREDIT, OpenAiResponses.errorReason(429, """{"error":{"type":"insufficient_quota","code":"insufficient_quota"}}"""))
        assertEquals(Reason.RATE_LIMITED, OpenAiResponses.errorReason(429, """{"error":{"code":"rate_limit_exceeded"}}"""))
        assertEquals(Reason.MODEL_UNAVAILABLE, OpenAiResponses.errorReason(404, """{"error":{"code":"model_not_found"}}"""))
        assertEquals(Reason.MODEL_UNAVAILABLE, OpenAiResponses.errorReason(400, """{"error":{"code":"model_not_found"}}"""))
        assertEquals(Reason.HTTP_ERROR, OpenAiResponses.errorReason(500, "not json"))
    }

    @Test
    fun keyProblemsAreFlagged() {
        assertEquals(true, Reason.KEY_REJECTED.keyProblem)
        assertEquals(false, Reason.DEADLINE.keyProblem)
    }

    @Test
    fun wrappingQuotesAreDropped() {
        assertEquals("See you at 4.", Cleaner.tidy("  \"See you at 4.\" ", "see you at four"))
        assertEquals("\"Quoted\" on purpose", Cleaner.tidy("“Quoted” on purpose", "quote quoted unquote on purpose"))
        assertEquals("\"Hi\"", Cleaner.tidy("\"Hi\"", "\"hi\""))
    }

    @Test
    fun curlyApostrophesAndQuotesAreStraightened() {
        assertEquals("We'll be there, I'm sure", Cleaner.tidy("We’ll be there, I’m sure", "we will be there I'm sure"))
        assertEquals("He said \"no\" twice", Cleaner.tidy("He said “no” twice", "he said quote no unquote twice"))
    }

    @Test
    fun onlyARefusalOfTheCacheParameterDropsIt() {
        assertTrue(OpenAiResponses.rejectsRetention(400, """{"error":{"message":"Unsupported parameter: 'prompt_cache_retention'","param":"prompt_cache_retention"}}"""))
        assertTrue(OpenAiResponses.rejectsRetention(400, """{"error":{"message":"Unknown parameter: 'prompt_cache_retention'."}}"""))
        assertFalse(OpenAiResponses.rejectsRetention(400, """{"error":{"message":"Bad temperature","param":"temperature"}}"""))
        assertFalse(OpenAiResponses.rejectsRetention(401, """{"error":{"param":"prompt_cache_retention"}}"""))
        assertFalse(OpenAiResponses.rejectsRetention(400, "not json"))
    }

    /** A provider that plays back [pieces] as the answer streams, then ends with [end]. */
    private class Fake(val pieces: List<String>, val end: (String) -> Streamed) : CleanupProvider {
        var calls = 0
        var seen = 0
        override fun prewarm() {}
        override fun stream(call: CleanupCall, deadlineMs: Long, onText: (String) -> Boolean): Streamed {
            calls++
            for (p in pieces) {
                seen++
                if (!onText(p)) return Streamed.Stopped(10, 20, null)
            }
            return end(pieces.lastOrNull().orEmpty())
        }
    }

    private val config = CleanupConfig.DEFAULT
    private val talk = "um so I think we should meet at the cafe tomorrow"

    @Test
    fun aReplyIsStoppedOnItsFirstWords() {
        val fake = Fake(listOf("Sure", "Sure, here", "Sure, here is", "Sure, here is a plan")) { Streamed.Done(it, 1, 2, null, 0, 0) }
        val r = Cleaner({ "key" }, provider = fake).clean(talk, AppStyle.MESSAGING, emptyList(), config, 3000)
        assertEquals(Reason.ASSISTANT, (r as Cleaner.Result.Fallback).reason)
        assertEquals(3, fake.seen)
    }

    @Test
    fun aFinishedAnswerIsTidied() {
        val fake = Fake(listOf("“So I think we’ll meet", "“So I think we’ll meet at the café tomorrow.”")) { Streamed.Done(it, 5, 9, null, 1000, 1200) }
        val r = Cleaner({ "key" }, provider = fake).clean(talk, AppStyle.MESSAGING, emptyList(), config, 3000)
        assertEquals(Cleaner.Result.Cleaned("So I think we'll meet at the café tomorrow.", 5, 9, 1000, 1200), r)
    }

    @Test
    fun aProviderFailureComesThrough() {
        val fake = Fake(emptyList()) { Streamed.Failed(Reason.RATE_LIMITED, -1, 40, null, 429) }
        val r = Cleaner({ "key" }, provider = fake).clean(talk, AppStyle.MESSAGING, emptyList(), config, 3000)
        assertEquals(Cleaner.Result.Fallback(Reason.RATE_LIMITED, 40, 429, -1), r)
    }

    @Test
    fun noKeyShortAndAlreadyCleanNeverCallTheProvider() {
        val fake = Fake(emptyList()) { Streamed.Done(it, 1, 2, null, 0, 0) }
        assertEquals(Reason.NO_KEY, (Cleaner({ null }, provider = fake).clean(talk, AppStyle.MESSAGING, emptyList(), config, 3000) as Cleaner.Result.Fallback).reason)
        assertEquals(Reason.SHORT, (Cleaner({ "key" }, provider = fake).clean("Sounds good.", AppStyle.MESSAGING, emptyList(), config, 3000) as Cleaner.Result.Fallback).reason)
        assertEquals(Reason.CLEAN, (Cleaner({ "key" }, provider = fake).clean("I'll send it over tonight.", AppStyle.MESSAGING, emptyList(), config, 3000) as Cleaner.Result.Fallback).reason)
        assertEquals(0, fake.calls)
    }
}
