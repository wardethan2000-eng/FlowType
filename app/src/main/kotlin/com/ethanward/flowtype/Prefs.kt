package com.ethanward.flowtype

import android.content.Context
import com.ethanward.flowtype.asr.AsrModels

/** Small settings shared by the screens and the service. */
class Prefs(context: Context) {
    companion object {
        const val TEST_PHRASE_MS = 30 * 60 * 1000L
    }

    private val prefs = context.getSharedPreferences("flowtype", Context.MODE_PRIVATE)

    var modelId: String
        get() = prefs.getString("model", null) ?: AsrModels.DEFAULT.id
        set(value) = prefs.edit().putString("model", value).apply()

    /**
     * Insertion test: the button inserts a fixed phrase instead of dictating.
     * It turns itself off after 30 minutes, so it can't be left on by mistake.
     */
    var testPhraseMode: Boolean
        get() = System.currentTimeMillis() < prefs.getLong("test_phrase_until", 0)
        set(value) = prefs.edit()
            .putLong("test_phrase_until", if (value) System.currentTimeMillis() + TEST_PHRASE_MS else 0)
            .remove("test_phrase")
            .apply()

    var asrThreads: Int
        get() = prefs.getInt("asr_threads", 4)
        set(value) = prefs.edit().putInt("asr_threads", value).apply()

    /** Where the button was dragged to, per orientation; null = just above the keyboard. */
    fun buttonPosition(landscape: Boolean): Pair<Int, Int>? {
        val key = if (landscape) "button_land" else "button_port"
        if (!prefs.contains("${key}_x")) return null
        return prefs.getInt("${key}_x", 0) to prefs.getInt("${key}_y", 0)
    }

    fun setButtonPosition(landscape: Boolean, x: Int, y: Int) {
        val key = if (landscape) "button_land" else "button_port"
        prefs.edit().putInt("${key}_x", x).putInt("${key}_y", y).apply()
    }

    fun resetButtonPosition() {
        prefs.edit().remove("button_port_x").remove("button_port_y")
            .remove("button_land_x").remove("button_land_y").apply()
    }
}
