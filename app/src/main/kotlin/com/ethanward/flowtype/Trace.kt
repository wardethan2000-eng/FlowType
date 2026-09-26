package com.ethanward.flowtype

import android.util.Log

/**
 * The only logging in the app. Callers pass numbers, enums and package names:
 * never field text, transcripts or the API key (AGENTS.md).
 */
object Trace {
    private const val TAG = "Flowtype"

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, format(name, *fields))
    }

    fun warn(name: String, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(name, *fields))
    }

    fun format(name: String, vararg fields: Pair<String, Any?>): String =
        buildString {
            append(name)
            for ((k, v) in fields) append(' ').append(k).append('=').append(v)
        }
}
