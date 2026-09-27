package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.cleanup.Cleaner.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanerTest {
    @Test
    fun errorResponsesMapToWhatTheUserCanFix() {
        assertEquals(Reason.KEY_REJECTED, Cleaner.errorReason(401, """{"error":{"code":"invalid_api_key"}}"""))
        assertEquals(Reason.NO_CREDIT, Cleaner.errorReason(429, """{"error":{"type":"insufficient_quota","code":"insufficient_quota"}}"""))
        assertEquals(Reason.RATE_LIMITED, Cleaner.errorReason(429, """{"error":{"code":"rate_limit_exceeded"}}"""))
        assertEquals(Reason.MODEL_UNAVAILABLE, Cleaner.errorReason(404, """{"error":{"code":"model_not_found"}}"""))
        assertEquals(Reason.MODEL_UNAVAILABLE, Cleaner.errorReason(400, """{"error":{"code":"model_not_found"}}"""))
        assertEquals(Reason.HTTP_ERROR, Cleaner.errorReason(500, "not json"))
    }

    @Test
    fun keyProblemsAreFlagged() {
        assertEquals(true, Reason.KEY_REJECTED.keyProblem)
        assertEquals(false, Reason.DEADLINE.keyProblem)
    }

    @Test
    fun wrappingQuotesAreDropped() {
        assertEquals("See you at 4.", Cleaner.tidy("  \"See you at 4.\" ", "see you at four"))
        assertEquals("“Quoted” on purpose", Cleaner.tidy("“Quoted” on purpose", "quote quoted unquote on purpose"))
        assertEquals("\"Hi\"", Cleaner.tidy("\"Hi\"", "\"hi\""))
    }

    @Test
    fun onlyARefusalOfTheCacheParameterDropsIt() {
        assertTrue(Cleaner.rejectsRetention(400, """{"error":{"message":"Unsupported parameter: 'prompt_cache_retention'","param":"prompt_cache_retention"}}"""))
        assertTrue(Cleaner.rejectsRetention(400, """{"error":{"message":"Unknown parameter: 'prompt_cache_retention'."}}"""))
        assertFalse(Cleaner.rejectsRetention(400, """{"error":{"message":"Bad temperature","param":"temperature"}}"""))
        assertFalse(Cleaner.rejectsRetention(401, """{"error":{"param":"prompt_cache_retention"}}"""))
        assertFalse(Cleaner.rejectsRetention(400, "not json"))
    }
}
