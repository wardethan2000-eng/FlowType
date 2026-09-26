package com.ethanward.flowtype.cleanup

import org.json.JSONArray
import org.json.JSONObject

/** One model/tier setup the timing test compares (PLAN §5 dials). */
data class CleanupConfig(
    val label: String,
    val model: String,
    /** Luna's reasoning effort; null for models without reasoning (gpt-4.1-nano). */
    val reasoningEffort: String?,
    /** null = the default tier (field omitted). */
    val serviceTier: String?,
) {
    companion object {
        val LUNA = CleanupConfig("Luna default", "gpt-6-luna", "none", null)
        val LUNA_FAST = CleanupConfig("Luna fast", "gpt-6-luna", "none", "fast")
        val NANO = CleanupConfig("gpt-4.1-nano", "gpt-4.1-nano", null, null)
        val ALL = listOf(LUNA, LUNA_FAST, NANO)
    }
}

/** Builds the Responses API body (PLAN §4.5). */
object CleanupRequest {
    const val URL = "https://api.openai.com/v1/responses"
    const val CACHE_KEY = "flowtype-cleanup-v${CleanupPrompt.VERSION}"

    fun body(
        config: CleanupConfig,
        transcript: String,
        dictionary: List<String>,
        style: String,
        beforeCursor: String? = null,
    ): JSONObject = JSONObject().apply {
        put("model", config.model)
        put("instructions", CleanupPrompt.instructions(dictionary))
        put(
            "input",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", CleanupPrompt.userMessage(transcript, style, beforeCursor)),
            ),
        )
        // Luna takes a temperature only at effort "none"; anything else is a 400.
        config.reasoningEffort?.let { put("reasoning", JSONObject().put("effort", it)) }
        put("temperature", 0)
        put("store", false)
        put("stream", true)
        put("max_output_tokens", CleanupPrompt.maxOutputTokens(transcript))
        put("prompt_cache_key", CACHE_KEY)
        config.serviceTier?.let { put("service_tier", it) }
    }
}
