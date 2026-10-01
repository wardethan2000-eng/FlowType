package com.ethanward.flowtype.cleanup

/**
 * One way of talking to a cleanup model (LAUNCH §A): it builds the request,
 * streams the answer, turns the provider's errors into a [Cleaner.Reason] and
 * reports the tokens used. Everything that isn't wire format (the short and
 * already-clean skips, the early "sounds like a reply" stop, guards, tidying)
 * stays in [Cleaner], so every provider gets it.
 *
 * Implementations block; run them off the main thread. Never log text or keys.
 */
interface CleanupProvider {
    /** Opens the connection while you're still talking, so the call skips DNS, TCP and TLS. No key is sent. */
    fun prewarm()

    /**
     * Streams one cleanup, giving up after [deadlineMs]. [onText] sees the
     * answer so far after each piece arrives, and returns false to stop
     * (the request is cancelled and [Streamed.Stopped] comes back).
     */
    fun stream(call: CleanupCall, deadlineMs: Long, onText: (String) -> Boolean): Streamed
}

/** What one cleanup asks for. */
data class CleanupCall(
    val key: String,
    val config: CleanupConfig,
    val transcript: String,
    val dictionary: List<String>,
    val style: String,
    /** The last ~80 characters before the cursor, or null. */
    val beforeCursor: String?,
)

/**
 * How a streamed cleanup ended. [usage] is what to add to the cost meter:
 * null when nothing was billed (the request never got an answer started).
 * [firstTokenMs] is -1 when no text arrived.
 */
sealed interface Streamed {
    val totalMs: Long
    val firstTokenMs: Long
    val usage: Usage?

    data class Done(
        val text: String,
        override val firstTokenMs: Long,
        override val totalMs: Long,
        override val usage: Usage?,
        /** As the provider reported them; -1 when it didn't. */
        val cachedTokens: Int,
        val inputTokens: Int,
    ) : Streamed

    /** [CleanupProvider.stream]'s onText asked to stop. */
    data class Stopped(override val firstTokenMs: Long, override val totalMs: Long, override val usage: Usage?) : Streamed

    data class Failed(
        val reason: Cleaner.Reason,
        override val firstTokenMs: Long,
        override val totalMs: Long,
        override val usage: Usage?,
        val http: Int = 0,
    ) : Streamed
}
