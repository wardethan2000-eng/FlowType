package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.Trace
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Any OpenAI-compatible Chat Completions server (LAUNCH §A1): Gemini, Groq,
 * OpenRouter, Mistral, DeepSeek, Together, a self-hosted server, or Ollama or
 * LM Studio on your own computer. [baseUrl] is the part before
 * `/chat/completions`, such as `https://api.groq.com/openai/v1`. A local
 * server may need no key: a blank one isn't sent.
 */
class ChatCompletions(baseUrl: String) : CleanupProvider {
    private val base = baseUrl.trim().trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Cached tokens on the last complete answer: the best guess for one cut short. */
    @Volatile private var lastCached = 0

    override fun prewarm() {
        val req = Request.Builder().url("$base/models").head().build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: okhttp3.Response) = response.close()
        })
    }

    override fun stream(call: CleanupCall, deadlineMs: Long, onText: (String) -> Boolean): Streamed {
        val body = body(call)
        val request = Request.Builder()
            .url("$base/chat/completions")
            .apply { if (call.key.isNotBlank()) header("Authorization", "Bearer ${call.key}") }
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val http = client.newCall(request)
        http.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        var firstTokenMs = -1L
        var streamed: ChatStream? = null
        var billed = false
        fun usage() = if (billed) usageOf(streamed, body.toString()) else null
        try {
            http.execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Streamed.Failed(errorReason(resp.code, resp.body?.string().orEmpty()), firstTokenMs, ms(), null, resp.code)
                }
                billed = true
                val s = ChatStream().also { streamed = it }
                val source = resp.body!!.source()
                while (!s.done) {
                    val line = source.readUtf8Line() ?: break
                    val had = s.text.length
                    if (s.line(line)) firstTokenMs = ms()
                    if (s.text.length != had && !onText(s.text.toString())) {
                        http.cancel()
                        return Streamed.Stopped(firstTokenMs, ms(), usage())
                    }
                }
                s.line("")
                if (s.error != null) return Streamed.Failed(Cleaner.Reason.HTTP_ERROR, firstTokenMs, ms(), usage(), resp.code)
                return Streamed.Done(s.text.toString(), firstTokenMs, ms(), usage(), s.cachedTokens, s.inputTokens)
            }
        } catch (e: InterruptedIOException) {
            billed = true // the server may finish, and bill, a request we stopped waiting for
            return Streamed.Failed(Cleaner.Reason.DEADLINE, firstTokenMs, ms(), usage())
        } catch (e: UnknownHostException) {
            return Streamed.Failed(Cleaner.Reason.OFFLINE, firstTokenMs, ms(), usage())
        } catch (e: ConnectException) {
            return Streamed.Failed(Cleaner.Reason.OFFLINE, firstTokenMs, ms(), usage())
        } catch (e: IOException) {
            Trace.warn("cleanup_io", "error" to e.javaClass.simpleName)
            val reason = if (http.isCanceled()) Cleaner.Reason.DEADLINE else Cleaner.Reason.ERROR
            return Streamed.Failed(reason, firstTokenMs, ms(), usage())
        }
    }

    /** Tokens as the server reported them, or estimated for a request cut short or a server that doesn't say. */
    private fun usageOf(stream: ChatStream?, requestBody: String): Usage =
        if (stream != null && stream.inputTokens >= 0) {
            lastCached = maxOf(0, stream.cachedTokens)
            Usage(stream.inputTokens, maxOf(0, stream.cachedTokens), maxOf(0, stream.outputTokens))
        } else {
            val input = CleanupPrompt.estimateTokens(requestBody)
            Usage(input, minOf(lastCached, input), CleanupPrompt.estimateTokens(stream?.text?.toString().orEmpty()), estimated = true)
        }

    companion object {
        /**
         * The request: the cached rules as the system message, then style,
         * context and transcript. Most servers cache a repeated prefix by
         * themselves; none of them need a cache key.
         */
        fun body(call: CleanupCall): JSONObject = JSONObject().apply {
            put("model", call.config.model)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", CleanupPrompt.instructions(call.dictionary)))
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            CleanupPrompt.userMessage(call.transcript, call.style, call.beforeCursor?.takeLast(80)?.ifBlank { null }),
                        ),
                    ),
            )
            put("temperature", 0)
            put("max_tokens", CleanupPrompt.maxOutputTokens(call.transcript))
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
        }

        /**
         * What an error response means. Servers differ: OpenRouter says 402
         * for no credit, OpenAI-style servers say 429 with insufficient_quota,
         * and an unknown model is a 404 or a 400 that names the model.
         */
        fun errorReason(http: Int, body: String): Cleaner.Reason {
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val code = error?.opt("code")?.toString().orEmpty()
            val type = error?.optString("type").orEmpty()
            val message = error?.optString("message").orEmpty().lowercase()
            return when {
                http == 401 -> Cleaner.Reason.KEY_REJECTED
                http == 402 -> Cleaner.Reason.NO_CREDIT
                http == 429 && (code == "insufficient_quota" || type == "insufficient_quota" || "credit" in message || "quota" in message) ->
                    Cleaner.Reason.NO_CREDIT
                http == 429 -> Cleaner.Reason.RATE_LIMITED
                http == 404 || http == 403 || code == "model_not_found" -> Cleaner.Reason.MODEL_UNAVAILABLE
                http == 400 && "model" in message -> Cleaner.Reason.MODEL_UNAVAILABLE
                else -> Cleaner.Reason.HTTP_ERROR
            }
        }
    }
}
