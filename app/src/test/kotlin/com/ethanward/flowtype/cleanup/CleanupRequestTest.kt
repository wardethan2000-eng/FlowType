package com.ethanward.flowtype.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupRequestTest {
    @Test
    fun lunaGetsEffortNoneTemperatureZeroAndNoStorage() {
        val b = CleanupRequest.body(CleanupConfig.LUNA, "hello there", listOf("PETG"), "messaging")
        assertEquals("gpt-6-luna", b.getString("model"))
        assertEquals("none", b.getJSONObject("reasoning").getString("effort"))
        assertEquals(0, b.getInt("temperature"))
        assertFalse(b.getBoolean("store"))
        assertTrue(b.getBoolean("stream"))
        assertFalse(b.has("service_tier"))
        assertEquals(CleanupPrompt.instructions(listOf("PETG")), b.getString("instructions"))
        val input = b.getJSONArray("input").getJSONObject(0)
        assertEquals("user", input.getString("role"))
        assertTrue(input.getString("content").endsWith("<transcript>hello there</transcript>"))
    }

    @Test
    fun fastTierIsSentOnlyWhenAsked() {
        val b = CleanupRequest.body(CleanupConfig.LUNA_FAST, "x", emptyList(), "email")
        assertEquals("fast", b.getString("service_tier"))
    }

    @Test
    fun nanoHasNoReasoningField() {
        val b = CleanupRequest.body(CleanupConfig.NANO, "x", emptyList(), "email")
        assertEquals("gpt-4.1-nano", b.getString("model"))
        assertFalse(b.has("reasoning"))
        assertFalse(b.getBoolean("store"))
    }
}
