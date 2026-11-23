package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.Trace
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * OpenAI's Responses API (PLAN §4.5): one streamed call per cleanup, with the
 * day-long prompt cache and the fast tier where the model has them.
 */
class OpenAiResponses : CleanupProvider {
    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** OpenAI refused prompt_cache_retention once; this process stops sending it. */
    @Volatile private var retentionRejected = false

    /** Cached tokens on the last complete answer: the best guess for one cut short. */
    @Volatile private var lastCached = 0

    override fun prewarm() {
        val req = Request.Builder().url("https://api.openai.com/v1/models").head().build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: okhttp3.Response) = response.close()
        })
    }

    override fun stream(call: CleanupCall, deadlineMs: Long, onText: (String) -> Boolean): Streamed {
        val config = call.config
        val retention = if (retentionRejected) null else CleanupRequest.CACHE_RETENTION
        val body = CleanupRequest.body(
            config, call.transcript, call.dictionary, call.style,
            call.beforeCursor?.takeLast(80)?.ifBlank { null }, retention,
        )
        val request = Request.Builder()
            .url(CleanupRequest.URL)
            .header("Authorization", "Bearer ${call.key}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val http = client.newCall(request)
        http.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        var firstTokenMs = -1L
        var streamed: ResponseStream? = null
        var billed = false
        fun usage() = if (billed) usageOf(streamed, body.toString()) else null
        try {
            http.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val error = resp.body?.string().orEmpty()
                    if (retention != null && rejectsRetention(resp.code, error)) {
                        // A model without the day-long cache: stop asking, and retry at once.
                        retentionRejected = true
                        Trace.warn("cache_retention_rejected", "model" to config.model)
                        return stream(call, maxOf(1, deadlineMs - ms()), onText)
                    }
                    return Streamed.Failed(errorReason(resp.code, error), firstTokenMs, ms(), null, resp.code)
                }
                billed = true
                val s = ResponseStream().also { streamed = it }
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
            billed = true // OpenAI may finish, and bill, a request we stopped waiting for
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

    /** Tokens as OpenAI reported them, or estimated for a request cut short. */
    private fun usageOf(stream: ResponseStream?, requestBody: String): Usage =
        if (stream != null && stream.inputTokens >= 0) {
            lastCached = maxOf(0, stream.cachedTokens)
            Usage(stream.inputTokens, maxOf(0, stream.cachedTokens), maxOf(0, stream.outputTokens))
        } else {
            val input = CleanupPrompt.estimateTokens(requestBody)
            Usage(input, minOf(lastCached, input), CleanupPrompt.estimateTokens(stream?.text?.toString().orEmpty()), estimated = true)
        }

    companion object {
        /** What an error response means, in the terms the screens use. */
        fun errorReason(http: Int, body: String): Cleaner.Reason {
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val code = error?.optString("code").orEmpty()
            val type = error?.optString("type").orEmpty()
            return when {
                http == 401 -> Cleaner.Reason.KEY_REJECTED
                http == 429 && (code == "insufficient_quota" || type == "insufficient_quota") -> Cleaner.Reason.NO_CREDIT
                http == 429 -> Cleaner.Reason.RATE_LIMITED
                http == 404 || code == "model_not_found" -> Cleaner.Reason.MODEL_UNAVAILABLE
                http == 403 -> Cleaner.Reason.MODEL_UNAVAILABLE
                else -> Cleaner.Reason.HTTP_ERROR
            }
        }

        /** A 400 that names the cache retention parameter, i.e. this model can't keep it. */
        fun rejectsRetention(http: Int, body: String): Boolean {
            if (http != 400) return false
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull() ?: return false
            return error.optString("param") == "prompt_cache_retention" ||
                error.optString("message").contains("prompt_cache_retention")
        }
    }
}
