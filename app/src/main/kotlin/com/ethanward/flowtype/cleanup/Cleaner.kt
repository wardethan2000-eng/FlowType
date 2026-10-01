package com.ethanward.flowtype.cleanup

import com.ethanward.flowtype.Trace
import com.ethanward.flowtype.dictionary.SnippetTokens

/**
 * AI cleanup (PLAN §4.5): one streamed call per dictation through a
 * [CleanupProvider], with the guards and a deadline. Every failure path
 * returns [Result.Fallback], and the caller types the local text: dictation
 * never stops working because of the network or the key.
 *
 * Calls block; run them off the main thread. Never logs text or the key.
 */
class Cleaner(
    /** The saved key, or null ([ApiKeyStore.load]). */
    private val key: () -> String?,
    private val usage: UsageStore? = null,
    private val provider: CleanupProvider = OpenAiResponses(),
) {

    enum class Reason(val keyProblem: Boolean = false) {
        NO_KEY, SHORT, CLEAN, OFFLINE, DEADLINE,
        KEY_REJECTED(true), NO_CREDIT(true), RATE_LIMITED, MODEL_UNAVAILABLE(true), HTTP_ERROR,
        TOO_LONG, TOO_SHORT, ASSISTANT, SNIPPETS, EMPTY, ERROR,
    }

    sealed interface Result {
        data class Cleaned(val text: String, val firstTokenMs: Long, val totalMs: Long, val cachedTokens: Int, val inputTokens: Int) : Result
        /** [firstTokenMs]: when the answer started arriving, -1 if it never did (tells a slow start from a slow stream). */
        data class Fallback(val reason: Reason, val totalMs: Long = 0, val http: Int = 0, val firstTokenMs: Long = -1) : Result
    }

    /**
     * Opens the HTTPS connection while you're still talking, so the cleanup
     * call doesn't pay for DNS, TCP and TLS (PLAN §2). No key is sent.
     */
    fun prewarm() = provider.prewarm()

    fun clean(
        input: String,
        style: String,
        dictionary: List<String>,
        config: CleanupConfig,
        deadlineMs: Long,
        beforeCursor: String? = null,
    ): Result {
        val key = key() ?: return Result.Fallback(Reason.NO_KEY)
        // "sounds good", "on my way": already right, and instant without the network.
        // Snippets are typed as saved: only the words around them count here.
        val spoken = SnippetTokens.strip(input)
        if (Guards.contentWords(spoken).size <= SHORT_WORDS) return Result.Fallback(Reason.SHORT)
        // Short and plain: the phone's text is already the answer, a second sooner.
        if (AlreadyClean.check(spoken, style)) return Result.Fallback(Reason.CLEAN)
        return request(CleanupCall(key, config, input, dictionary, style, beforeCursor), deadlineMs)
    }

    /** The Test key button: a tiny real cleanup with a generous deadline. */
    fun test(key: String, config: CleanupConfig): Result =
        request(CleanupCall(key, config, "um so this is a quick test of my key", emptyList(), AppStyle.GENERAL, null), 15_000)

    private fun request(call: CleanupCall, deadlineMs: Long): Result {
        val input = call.transcript
        var judged = false
        val streamed = provider.stream(call, deadlineMs) { soFar ->
            // Early stop: an answer that opens like a reply is thrown away on
            // its first words, not after the whole reply.
            if (!judged && Guards.canJudgeStart(soFar, finished = false)) {
                judged = true
                if (Guards.soundsLikeAssistant(input, soFar)) return@stream false
            }
            true
        }
        streamed.usage?.let { u -> usage?.let { store -> runCatching { store.record(call.config, u) } } }
        return when (streamed) {
            is Streamed.Stopped -> Result.Fallback(Reason.ASSISTANT, streamed.totalMs)
            is Streamed.Failed -> Result.Fallback(streamed.reason, streamed.totalMs, streamed.http, streamed.firstTokenMs)
            is Streamed.Done -> judge(call, streamed)
        }
    }

    /** The guards on a finished answer. */
    private fun judge(call: CleanupCall, done: Streamed.Done): Result {
        val input = call.transcript
        val output = tidy(done.text, input)
        val verdict = Guards.check(input, output)
        if (verdict == Guards.Verdict.OK) {
            return Result.Cleaned(output, done.firstTokenMs, done.totalMs, done.cachedTokens, done.inputTokens)
        }
        // Counts only, to tune the guards: which answers they throw away, and by how much.
        Trace.event(
            "guard_rejected", "verdict" to verdict, "style" to call.style,
            "wordsIn" to Guards.contentWords(input).size, "wordsOut" to Guards.words(output).size,
            "rawWordsIn" to Guards.words(input).size,
        )
        val reason = when (verdict) {
            Guards.Verdict.TOO_LONG -> Reason.TOO_LONG
            Guards.Verdict.TOO_SHORT -> Reason.TOO_SHORT
            Guards.Verdict.ASSISTANT -> Reason.ASSISTANT
            Guards.Verdict.SNIPPETS -> Reason.SNIPPETS
            else -> Reason.EMPTY
        }
        return Result.Fallback(reason, done.totalMs)
    }

    companion object {
        const val SHORT_WORDS = 3

        /**
         * Trims the answer, drops quotes it wrapped around the whole thing, and
         * straightens its curly quotes and apostrophes: the phone's text and the
         * keyboard use straight ones, and "We’ll" beside "I'm" looks wrong.
         */
        fun tidy(output: String, input: String): String {
            var t = output.trim()
            val quoted = t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))
            if (quoted && !input.trim().startsWith("\"")) t = t.substring(1, t.length - 1).trim()
            return t.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        }
    }
}
