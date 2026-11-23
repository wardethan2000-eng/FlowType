package com.ethanward.flowtype

/**
 * The only logging in Flowtype. Callers pass numbers, enums and package names:
 * never field text, transcripts or the API key (AGENTS.md).
 *
 * Where lines go is the platform's: the phone sets [sink] to logcat at start-up
 * (FlowtypeApp), the desktop command to stderr. Until then they're dropped.
 */
object Trace {
    enum class Level { INFO, WARN }

    @Volatile
    var sink: (Level, String) -> Unit = { _, _ -> }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        sink(Level.INFO, format(name, *fields))
    }

    fun warn(name: String, vararg fields: Pair<String, Any?>) {
        sink(Level.WARN, format(name, *fields))
    }

    fun format(name: String, vararg fields: Pair<String, Any?>): String =
        buildString {
            append(name)
            for ((k, v) in fields) append(' ').append(k).append('=').append(v)
        }
}
