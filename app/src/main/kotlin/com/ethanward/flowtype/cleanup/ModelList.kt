package com.ethanward.flowtype.cleanup

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The models a provider offers, asked of the provider itself (its `/models`
 * list), so the picker never goes stale and nobody has to type an id. Blocks;
 * run it off the main thread.
 */
object ModelList {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed interface Answer {
        data class Models(val ids: List<String>) : Answer
        data class Failed(val reason: Cleaner.Reason, val http: Int = 0) : Answer
    }

    fun fetch(preset: ProviderPreset, baseUrl: String, key: String): Answer {
        val request = when (preset.protocol) {
            Protocol.ANTHROPIC -> Request.Builder().url("${baseUrl.trimEnd('/')}/v1/models?limit=1000")
                .header("x-api-key", key)
                .header("anthropic-version", AnthropicMessages.VERSION)
            else -> Request.Builder().url("${baseUrl.trimEnd('/')}/models")
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
        }.get().build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val reason = if (preset.protocol == Protocol.ANTHROPIC) AnthropicMessages.errorReason(resp.code, body)
                    else ChatCompletions.errorReason(resp.code, body)
                    return Answer.Failed(reason, resp.code)
                }
                Answer.Models(parse(body))
            }
        }.getOrElse { Answer.Failed(if (it is java.net.UnknownHostException || it is java.net.ConnectException) Cleaner.Reason.OFFLINE else Cleaner.Reason.ERROR) }
    }

    /** `{"data":[{"id":"…"}, …]}`: the ids, sorted, without repeats. */
    fun parse(body: String): List<String> {
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            .distinct().sorted()
    }
}
