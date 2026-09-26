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
 * AI cleanup (PLAN §4.5): one streamed Responses API call per dictation, with
 * the guards and a deadline. Every failure path returns [Result.Fallback], and
 * the caller types the local text: dictation never stops working because of
 * the network or the key.
 *
 * Calls block; run them off the main thread. Never logs text or the key.
 */
class Cleaner(private val keys: ApiKeyStore, private val usage: UsageStore? = null) {

    enum class Reason(val keyProblem: Boolean = false) {
        NO_KEY, SHORT, OFFLINE, DEADLINE,
        KEY_REJECTED(true), NO_CREDIT(true), RATE_LIMITED, MODEL_UNAVAILABLE(true), HTTP_ERROR,
        TOO_LONG, TOO_SHORT, ASSISTANT, EMPTY, ERROR,
    }

    sealed interface Result {
        data class Cleaned(val text: String, val firstTokenMs: Long, val totalMs: Long, val cachedTokens: Int, val inputTokens: Int) : Result
        data class Fallback(val reason: Reason, val totalMs: Long = 0, val http: Int = 0) : Result
    }

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Opens the HTTPS connection while you're still talking, so the cleanup
     * call doesn't pay for DNS, TCP and TLS (PLAN §2). No key is sent.
     */
    fun prewarm() {
        val req = Request.Builder().url("https://api.openai.com/v1/models").head().build()
        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: okhttp3.Response) = response.close()
        })
    }

    fun clean(
        input: String,
        style: String,
        dictionary: List<String>,
        config: CleanupConfig,
        deadlineMs: Long,
        beforeCursor: String? = null,
    ): Result {
        val key = keys.load() ?: return Result.Fallback(Reason.NO_KEY)
        // "sounds good", "on my way": already right, and instant without the network.
        if (Guards.contentWords(input).size <= SHORT_WORDS) return Result.Fallback(Reason.SHORT)
        return request(key, input, style, dictionary, config, deadlineMs, beforeCursor)
    }

    /** The Test key button: a tiny real cleanup with a generous deadline. */
    fun test(key: String, config: CleanupConfig): Result =
        request(key, "um so this is a quick test of my key", AppStyle.GENERAL, emptyList(), config, 15_000)

    private fun request(
        key: String,
        input: String,
        style: String,
        dictionary: List<String>,
        config: CleanupConfig,
        deadlineMs: Long,
        beforeCursor: String? = null,
    ): Result {
        val body = CleanupRequest.body(config, input, dictionary, style, beforeCursor?.takeLast(80)?.ifBlank { null })
        val request = Request.Builder()
            .url(CleanupRequest.URL)
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        var firstTokenMs = -1L
        var streamed: ResponseStream? = null
        var billed = false
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val reason = errorReason(resp.code, resp.body?.string().orEmpty())
                    return Result.Fallback(reason, ms(), resp.code)
                }
                billed = true
                val stream = ResponseStream().also { streamed = it }
                val source = resp.body!!.source()
                var judged = false
                while (!stream.done) {
                    val line = source.readUtf8Line() ?: break
                    if (stream.line(line)) firstTokenMs = ms()
                    // Early abort: an answer that opens like a reply is thrown
                    // away on its first words, not after the whole reply.
                    if (!judged && Guards.canJudgeStart(stream.text.toString(), finished = false)) {
                        judged = true
                        if (Guards.soundsLikeAssistant(input, stream.text.toString())) {
                            call.cancel()
                            return Result.Fallback(Reason.ASSISTANT, ms())
                        }
                    }
                }
                stream.line("")
                if (stream.error != null) return Result.Fallback(Reason.HTTP_ERROR, ms(), resp.code)
                val output = tidy(stream.text.toString(), input)
                val verdict = Guards.check(input, output)
                if (verdict != Guards.Verdict.OK) {
                    return Result.Fallback(
                        when (verdict) {
                            Guards.Verdict.TOO_LONG -> Reason.TOO_LONG
                            Guards.Verdict.TOO_SHORT -> Reason.TOO_SHORT
                            Guards.Verdict.ASSISTANT -> Reason.ASSISTANT
                            else -> Reason.EMPTY
                        },
                        ms(),
                    )
                }
                return Result.Cleaned(output, firstTokenMs, ms(), stream.cachedTokens, stream.inputTokens)
            }
        } catch (e: InterruptedIOException) {
            billed = true // OpenAI may finish, and bill, a request we stopped waiting for
            return Result.Fallback(Reason.DEADLINE, ms())
        } catch (e: UnknownHostException) {
            return Result.Fallback(Reason.OFFLINE, ms())
        } catch (e: ConnectException) {
            return Result.Fallback(Reason.OFFLINE, ms())
        } catch (e: IOException) {
            Trace.warn("cleanup_io", "error" to e.javaClass.simpleName)
            return Result.Fallback(if (call.isCanceled()) Reason.DEADLINE else Reason.ERROR, ms())
        } finally {
            if (billed) record(config, streamed, body.toString())
        }
    }

    /** Tokens as OpenAI reported them, or estimated for a request cut short. */
    private fun record(config: CleanupConfig, stream: ResponseStream?, requestBody: String) {
        val store = usage ?: return
        val s = stream
        val u = if (s != null && s.inputTokens >= 0) {
            lastCached = maxOf(0, s.cachedTokens)
            Usage(s.inputTokens, maxOf(0, s.cachedTokens), maxOf(0, s.outputTokens))
        } else {
            val input = CleanupPrompt.estimateTokens(requestBody)
            Usage(input, minOf(lastCached, input), CleanupPrompt.estimateTokens(s?.text?.toString().orEmpty()), estimated = true)
        }
        runCatching { store.record(config, u) }
    }

    /** Cached tokens on the last complete answer: the best guess for one cut short. */
    @Volatile private var lastCached = 0



    companion object {
        const val SHORT_WORDS = 3

        /** What an error response means, in the terms the screens use. */
        fun errorReason(http: Int, body: String): Reason {
            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val code = error?.optString("code").orEmpty()
            val type = error?.optString("type").orEmpty()
            return when {
                http == 401 -> Reason.KEY_REJECTED
                http == 429 && (code == "insufficient_quota" || type == "insufficient_quota") -> Reason.NO_CREDIT
                http == 429 -> Reason.RATE_LIMITED
                http == 404 || code == "model_not_found" -> Reason.MODEL_UNAVAILABLE
                http == 403 -> Reason.MODEL_UNAVAILABLE
                else -> Reason.HTTP_ERROR
            }
        }

        /** Trims the answer, and drops quotes it wrapped around the whole thing. */
        fun tidy(output: String, input: String): String {
            var t = output.trim()
            val quoted = t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))
            if (quoted && !input.trim().startsWith("\"")) t = t.substring(1, t.length - 1).trim()
            return t
        }
    }
}
