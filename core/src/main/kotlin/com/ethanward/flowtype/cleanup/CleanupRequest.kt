package com.ethanward.flowtype.cleanup

import org.json.JSONArray
import org.json.JSONObject

/** One model/tier setup the timing test compares (PLAN §5 dials). */
data class CleanupConfig(
    /** Stored in settings. */
    val id: String,
    val label: String,
    val model: String,
    /** Luna's reasoning effort; null for models without reasoning (gpt-4.1-nano). */
    val reasoningEffort: String?,
    /** null = the default tier (field omitted). */
    val serviceTier: String?,
) {
    companion object {
        val LUNA = CleanupConfig("luna", "Luna", "gpt-6-luna", "none", null)
        val LUNA_FAST = CleanupConfig("luna-fast", "Luna, fast tier", "gpt-6-luna", "none", "fast")
        val NANO = CleanupConfig("nano", "GPT-4.1 nano", "gpt-4.1-nano", null, null)
        val ALL = listOf(LUNA, LUNA_FAST, NANO)
        /** Ethan's pick, 2026-09-26: Luna's quicker tier, for twice Luna's (tiny) price. */
        val DEFAULT = LUNA_FAST

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: DEFAULT

        /** A model on a provider other than OpenAI: no reasoning or tier settings. */
        fun of(provider: ProviderPreset, model: String) = CleanupConfig("${provider.id}:$model", model, model, null, null)
    }
}

/** Builds the Responses API body (PLAN §4.5). */
object CleanupRequest {
    const val URL = "https://api.openai.com/v1/responses"
    const val CACHE_KEY = "flowtype-cleanup-v${CleanupPrompt.VERSION}"
    /**
     * Keep the cached prefix for a day, not the default few minutes: dictations
     * are often further apart than that, and a cold prefix cost ~0.4–1 s of
     * first token on the phone.
     */
    const val CACHE_RETENTION = "24h"

    fun body(
        config: CleanupConfig,
        transcript: String,
        dictionary: List<String>,
        style: String,
        beforeCursor: String? = null,
        cacheRetention: String? = CACHE_RETENTION,
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
        cacheRetention?.let { put("prompt_cache_retention", it) }
        config.serviceTier?.let { put("service_tier", it) }
    }
}
