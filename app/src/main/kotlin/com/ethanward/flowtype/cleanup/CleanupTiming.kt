package com.ethanward.flowtype.cleanup

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Phase 0 cleanup timing from the phone (PLAN §7 step 6): for each model/tier,
 * one request on a fresh connection (cold) and then several on the same one
 * (warm), timing connection setup, first streamed token and the whole answer,
 * and reading back how many prompt tokens came from the cache.
 */
class CleanupTiming(private val apiKey: String, private val usage: UsageStore? = null) {

    data class Run(
        val config: String,
        val index: Int,
        val cold: Boolean,
        val http: Int,
        val connectMs: Long,
        val headersMs: Long,
        val firstTokenMs: Long,
        val totalMs: Long,
        val inputTokens: Int,
        val cachedTokens: Int,
        val outputTokens: Int,
        val tier: String?,
        val error: String?,
        /** Shown on the timing screen only; never written to the results file. */
        val output: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("config", config).put("index", index).put("cold", cold).put("http", http)
            .put("connectMs", connectMs).put("headersMs", headersMs).put("firstTokenMs", firstTokenMs)
            .put("totalMs", totalMs).put("inputTokens", inputTokens).put("cachedTokens", cachedTokens)
            .put("outputTokens", outputTokens).put("tier", tier ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL).put("outputChars", output.length)

        fun describe(): String = buildString {
            append(if (cold) "cold" else "warm").append(" #").append(index)
            if (error != null) {
                append("  HTTP ").append(http).append("  ").append(error)
                return@buildString
            }
            append("  connect ").append(connectMs).append(" ms")
            append("  first token ").append(firstTokenMs).append(" ms")
            append("  total ").append(totalMs).append(" ms")
            append("  cached ").append(cachedTokens).append('/').append(inputTokens)
            append("  out ").append(outputTokens)
            tier?.let { append("  tier ").append(it) }
        }
    }

    /** Runs [runs] requests for [config]: the first cold, the rest warm. */
    fun measure(config: CleanupConfig, runs: Int, pauseMs: Long, onRun: (Run) -> Unit) {
        val client = newClient()
        try {
            for (i in 0 until runs) {
                val transcript = SAMPLES[i % SAMPLES.size]
                onRun(once(client, config, i, cold = i == 0, transcript = transcript))
                if (i < runs - 1) Thread.sleep(pauseMs)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun once(client: OkHttpClient, config: CleanupConfig, index: Int, cold: Boolean, transcript: String): Run {
        val timing = Timing()
        val body = CleanupRequest.body(config, transcript, DICTIONARY, "messaging")
        val request = Request.Builder()
            .url(CleanupRequest.URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .tag(Timing::class.java, timing)
            .build()
        val start = System.nanoTime()
        fun ms(t: Long) = if (t == 0L) -1 else (t - start) / 1_000_000
        val stream = ResponseStream()
        var firstToken = 0L
        var http = -1
        var error: String? = null
        try {
            client.newCall(request).execute().use { resp ->
                http = resp.code
                val source = resp.body!!.source()
                if (!resp.isSuccessful) {
                    val text = source.readUtf8()
                    error = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                        .getOrDefault("HTTP ${resp.code}")
                } else {
                    while (!stream.done) {
                        val line = source.readUtf8Line() ?: break
                        if (stream.line(line)) firstToken = System.nanoTime()
                    }
                    stream.line("")
                    error = stream.error
                }
            }
        } catch (e: Exception) {
            error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
        val end = System.nanoTime()
        if (stream.inputTokens >= 0) runCatching {
            usage?.record(config, Usage(stream.inputTokens, maxOf(0, stream.cachedTokens), maxOf(0, stream.outputTokens)))
        }
        return Run(
            config = config.label, index = index, cold = cold, http = http,
            connectMs = if (timing.connectStart == 0L) 0 else (timing.connectEnd - timing.connectStart) / 1_000_000,
            headersMs = ms(timing.headers), firstTokenMs = ms(firstToken), totalMs = (end - start) / 1_000_000,
            inputTokens = stream.inputTokens, cachedTokens = stream.cachedTokens, outputTokens = stream.outputTokens,
            tier = stream.serviceTier, error = error, output = stream.text.toString(),
        )
    }

    /** Filled in by [listener] for the call it is tagged on. */
    private class Timing {
        @Volatile var connectStart = 0L
        @Volatile var connectEnd = 0L
        @Volatile var headers = 0L
    }

    private val listener = object : EventListener() {
        private fun t(call: Call) = call.request().tag(Timing::class.java)
        override fun dnsStart(call: Call, domainName: String) {
            t(call)?.let { if (it.connectStart == 0L) it.connectStart = System.nanoTime() }
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            t(call)?.let { if (it.connectStart == 0L) it.connectStart = System.nanoTime() }
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            t(call)?.connectEnd = System.nanoTime()
        }
        override fun responseHeadersStart(call: Call) {
            t(call)?.headers = System.nanoTime()
        }
    }

    /** Its own pool, so the first request always opens a new connection. */
    private fun newClient() = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .eventListener(listener)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        val DICTIONARY = listOf("DecalForge", "PETG", "Bambu", "Ethan", "Flowtype", "Parakeet", "OrcaSlicer", "PLA")

        /** Made-up dictations of typical length: nothing of the user's is sent. */
        val SAMPLES = listOf(
            "um so I was thinking we could uh meet at three no four o'clock tomorrow to go over the decal forge settings",
            "hey can you check if the pet g spool arrived I think it was supposed to come on tuesday",
            "the print on the bambu failed again around layer forty so I'm going to like lower the speed and try again tonight",
            "what time are we leaving for the airport on friday and should I book the parking or will you",
            "I mean the demo went fine you know they liked the the new photo frames and want a quote by next week",
        )
    }
}
