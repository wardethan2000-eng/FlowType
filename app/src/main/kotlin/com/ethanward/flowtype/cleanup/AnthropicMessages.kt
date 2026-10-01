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
 * Anthropic's Messages API over plain HTTP (LAUNCH §A1; Ethan, 2026-10-01:
 * the official Java SDK grew the app from 48 to 74 MB). The rules go in the
 * system prompt with a one-hour cache marker. Claude Sonnet 5.5 caches from
 * 512 tokens; Claude Haiku 4.5 only from 4,096, which our ~1,500-token
 * prompt doesn't reach, so every Haiku call pays the full input price.
 */
class AnthropicMessages(baseUrl: String = URL) : CleanupProvider {
    private val base = baseUrl.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Cached tokens on the last complete answer: the best guess for one cut short. */
    @Volatile private var lastCached = 0

    override fun prewarm() {
        val req = Request.Builder().url("$base/v1/models").head().build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: okhttp3.Response) = response.close()
        })
    }

    override fun stream(call: CleanupCall, deadlineMs: Long, onText: (String) -> Boolean): Streamed {
        val body = body(call)
        val request = Request.Builder()
            .url("$base/v1/messages")
            .header("x-api-key", call.key)
            .header("anthropic-version", VERSION)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val http = client.newCall(request)
        http.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        var firstTokenMs = -1L
        var streamed: AnthropicStream? = null
        var billed = false
        fun usage() = if (billed) usageOf(streamed, body.toString()) else null
        try {
            http.execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Streamed.Failed(errorReason(resp.code, resp.body?.string().orEmpty()), firstTokenMs, ms(), null, resp.code)
                }
                billed = true
                val s = AnthropicStream().also { streamed = it }
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
                // An error event (overloaded mid-stream), or a decline: the phone's own text goes in.
                if (s.error != null || s.stopReason == "refusal") {
                    return Streamed.Failed(Cleaner.Reason.HTTP_ERROR, firstTokenMs, ms(), usage(), resp.code)
                }
                return Streamed.Done(s.text.toString(), firstTokenMs, ms(), usage(), s.cachedTokens(), s.inputTokens())
            }
        } catch (e: InterruptedIOException) {
            billed = true // Anthropic may finish, and bill, a request we stopped waiting for
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

    /** Tokens as Anthropic reported them (fresh input, cache reads and cache writes all count as input), or estimated. */
    private fun usageOf(stream: AnthropicStream?, requestBody: String): Usage =
        if (stream != null && stream.input >= 0) {
            lastCached = stream.cachedTokens()
            Usage(stream.inputTokens(), stream.cachedTokens(), maxOf(0, stream.output))
        } else {
            val input = CleanupPrompt.estimateTokens(requestBody)
            Usage(input, minOf(lastCached, input), CleanupPrompt.estimateTokens(stream?.text?.toString().orEmpty()), estimated = true)
        }

    companion object {
        const val URL = "https://api.anthropic.com"
        const val VERSION = "2023-06-01"

        /**
         * The request. What each model accepts differs:
         * - Haiku 4.5 thinks only when asked and takes a temperature: 0.
         * - Sonnet 5.5 refuses a temperature and thinks by default; thinking
         *   is turned off with `between_tools` (accepted at effort high or
         *   below), and effort is low.
         * - Any other model (typed in by the user): no temperature, low
         *   effort, and room in max_tokens for whatever thinking it does.
         */
        fun body(call: CleanupCall): JSONObject = JSONObject().apply {
            val model = call.config.model
            val haiku = model.startsWith("claude-haiku")
            val answer = CleanupPrompt.maxOutputTokens(call.transcript)
            put("model", model)
            put("max_tokens", if (haiku || model == SONNET_5_5) answer else maxOf(answer, 2_048))
            put("stream", true)
            put(
                "system",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", CleanupPrompt.instructions(call.dictionary))
                        .put("cache_control", JSONObject().put("type", "ephemeral").put("ttl", "1h")),
                ),
            )
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        CleanupPrompt.userMessage(call.transcript, call.style, call.beforeCursor?.takeLast(80)?.ifBlank { null }),
                    ),
                ),
            )
            when {
                haiku -> put("temperature", 0)
                model == SONNET_5_5 -> {
                    put("thinking", JSONObject().put("type", "between_tools"))
                    put("output_config", JSONObject().put("effort", "low"))
                }
                else -> put("output_config", JSONObject().put("effort", "low"))
            }
        }

        const val SONNET_5_5 = "claude-sonnet-5-5"

        /**
         * What an error response means. Anthropic's error types: a low credit
         * balance is a 400 invalid_request_error that says so; 529 is
         * "overloaded".
         */
        fun errorReason(http: Int, body: String): Cleaner.Reason {
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val type = error?.optString("type").orEmpty()
            val message = error?.optString("message").orEmpty().lowercase()
            return when {
                http == 401 || type == "authentication_error" -> Cleaner.Reason.KEY_REJECTED
                http == 402 || type == "billing_error" || (http == 400 && "credit" in message) -> Cleaner.Reason.NO_CREDIT
                http == 429 -> Cleaner.Reason.RATE_LIMITED
                http == 403 || http == 404 || type == "not_found_error" -> Cleaner.Reason.MODEL_UNAVAILABLE
                http == 400 && "model" in message -> Cleaner.Reason.MODEL_UNAVAILABLE
                else -> Cleaner.Reason.HTTP_ERROR
            }
        }
    }
}

/**
 * Reads the Messages API's server-sent events: text deltas (thinking
 * deltas are skipped), usage from message_start and message_delta, the stop
 * reason, and error events.
 */
class AnthropicStream {
    val text = StringBuilder()
    /** Fresh input tokens; -1 until message_start. */
    var input = -1
    var cacheRead = 0
    var cacheWrite = 0
    var output = -1
    var stopReason: String? = null
    var error: String? = null
    var stopped = false
        private set
    val done: Boolean get() = stopped || error != null

    fun inputTokens() = maxOf(0, input) + cacheRead + cacheWrite
    fun cachedTokens() = cacheRead

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
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        when (json.optString("type")) {
            "message_start" -> json.optJSONObject("message")?.optJSONObject("usage")?.let { u ->
                input = u.optInt("input_tokens", -1)
                cacheRead = u.optInt("cache_read_input_tokens", 0)
                cacheWrite = u.optInt("cache_creation_input_tokens", 0)
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return false
                if (delta.optString("type") != "text_delta") return false
                text.append(delta.optString("text"))
                if (!firstDeltaSeen) {
                    firstDeltaSeen = true
                    return true
                }
            }
            "message_delta" -> {
                json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { stopReason = it }
                json.optJSONObject("usage")?.optInt("output_tokens", -1)?.takeIf { it >= 0 }?.let { output = it }
            }
            "message_stop" -> stopped = true
            "error" -> error = json.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "error" }
        }
        return false
    }
}
