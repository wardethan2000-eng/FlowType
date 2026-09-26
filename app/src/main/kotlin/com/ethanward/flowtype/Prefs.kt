package com.ethanward.flowtype

import android.content.Context
import com.ethanward.flowtype.asr.AsrModels

/** Small settings shared by the screens and the service. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("flowtype", Context.MODE_PRIVATE)

    var modelId: String
        get() = prefs.getString("model", null) ?: AsrModels.DEFAULT.id
        set(value) = prefs.edit().putString("model", value).apply()

    /** Insertion test: the button inserts a fixed phrase instead of dictating. */
    var testPhraseMode: Boolean
        get() = prefs.getBoolean("test_phrase", false)
        set(value) = prefs.edit().putBoolean("test_phrase", value).apply()

    var asrThreads: Int
        get() = prefs.getInt("asr_threads", 4)
        set(value) = prefs.edit().putInt("asr_threads", value).apply()
}
