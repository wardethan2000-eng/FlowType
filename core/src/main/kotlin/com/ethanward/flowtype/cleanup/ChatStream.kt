package com.ethanward.flowtype.cleanup

import org.json.JSONObject

/**
 * Reads an OpenAI-compatible Chat Completions stream (`/chat/completions`,
 * `stream: true`): text deltas, the usage chunk sent with
 * `stream_options.include_usage`, the finish reason, and errors some
 * providers send inside the stream.
 */
class ChatStream {
    val text = StringBuilder()
    var inputTokens = -1
    var cachedTokens = -1
    var outputTokens = -1
    var finishReason: String? = null
    var error: String? = null
    /** `data: [DONE]` arrived. */
    var ended = false
        private set
    val done: Boolean get() = ended || error != null

    private var firstDeltaSeen = false
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
        if (payload == "[DONE]") {
            ended = true
            return false
        }
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        json.optJSONObject("error")?.let { e ->
            error = e.optString("message").ifEmpty { "error" }
            return false
        }
        json.optJSONObject("usage")?.let { u ->
            inputTokens = u.optInt("prompt_tokens", -1)
            outputTokens = u.optInt("completion_tokens", -1)
            cachedTokens = u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", -1) ?: -1
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return false
        choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finishReason = it }
        val delta = choice.optJSONObject("delta")?.optString("content").orEmpty()
        if (delta.isEmpty() || delta == "null") return false
        text.append(delta)
        if (!firstDeltaSeen) {
            firstDeltaSeen = true
            return true
        }
        return false
    }
}
