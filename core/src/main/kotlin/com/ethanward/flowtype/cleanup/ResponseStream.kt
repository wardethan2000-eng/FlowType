package com.ethanward.flowtype.cleanup

import org.json.JSONObject

/**
 * Reads the Responses API's server-sent events: text deltas, the final usage
 * (with cached_tokens), and errors.
 */
class ResponseStream {
    val text = StringBuilder()
    var firstDeltaSeen = false
        private set
    var inputTokens = -1
    var cachedTokens = -1
    var outputTokens = -1
    var reasoningTokens = -1
    var serviceTier: String? = null
    var status: String? = null
    var error: String? = null
    val done: Boolean get() = status != null || error != null

    private var data = StringBuilder()

    /** Feed one line of the stream (without its newline). Returns true on the first text delta. */
    fun line(line: String): Boolean {
        if (line.startsWith("data:")) {
            data.append(line.removePrefix("data:").trimStart())
            return false
        }
        if (line.isEmpty() && data.isNotEmpty()) {
            val payload = data.toString()
            data = StringBuilder()
            return event(payload)
        }
        return false
    }

    private fun event(payload: String): Boolean {
        if (payload == "[DONE]") return false
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        when (json.optString("type")) {
            "response.output_text.delta" -> {
                text.append(json.optString("delta"))
                if (!firstDeltaSeen) {
                    firstDeltaSeen = true
                    return true
                }
            }
            "response.completed", "response.incomplete", "response.failed" -> {
                val response = json.optJSONObject("response") ?: return false
                status = response.optString("status", "unknown")
                serviceTier = response.optString("service_tier").ifEmpty { null }
                response.optJSONObject("usage")?.let { usage ->
                    inputTokens = usage.optInt("input_tokens", -1)
                    outputTokens = usage.optInt("output_tokens", -1)
                    cachedTokens = usage.optJSONObject("input_tokens_details")?.optInt("cached_tokens", -1) ?: -1
                    reasoningTokens = usage.optJSONObject("output_tokens_details")?.optInt("reasoning_tokens", -1) ?: -1
                }
                response.optJSONObject("error")?.let { error = it.optString("message") }
            }
            "error" -> error = json.optString("message").ifEmpty { json.optJSONObject("error")?.optString("message") ?: "error" }
        }
        return false
    }
}
