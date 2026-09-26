package com.ethanward.flowtype

import android.content.Context
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.cleanup.CleanupConfig

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

    /** AI cleanup on or off. It also needs a saved key to run. */
    var cleanupEnabled: Boolean
        get() = prefs.getBoolean("cleanup_on", true)
        set(value) = prefs.edit().putBoolean("cleanup_on", value).apply()

    var cleanupModel: String
        get() = prefs.getString("cleanup_model", null) ?: CleanupConfig.DEFAULT.id
        set(value) = prefs.edit().putString("cleanup_model", value).apply()

    /** How long to wait for cleanup before typing the local text (PLAN §4.5). */
    var cleanupDeadlineMs: Long
        get() = prefs.getLong("cleanup_deadline_ms", 1800)
        set(value) = prefs.edit().putLong("cleanup_deadline_ms", value).apply()

    /** The last key problem (a Cleaner.Reason name), shown on the home screen; null when fine. */
    var keyProblem: String?
        get() = prefs.getString("key_problem", null)
        set(value) = prefs.edit().putString("key_problem", value).apply()

    /** The one-time "that used the clipboard" notice (PLAN §4.6 step 3). */
    var pasteNoticeShown: Boolean
        get() = prefs.getBoolean("paste_notice", false)
        set(value) = prefs.edit().putBoolean("paste_notice", value).apply()

    /** Days of dictation history kept on the phone; 0 = none (PLAN §4.7). */
    var historyDays: Int
        get() = prefs.getInt("history_days", 7)
        set(value) = prefs.edit().putInt("history_days", value).apply()

    /** Dictionary Words also catch sound-alikes ("bamboo" → "Bambu"). */
    var soundsLike: Boolean
        get() = prefs.getBoolean("sounds_like", true)
        set(value) = prefs.edit().putBoolean("sounds_like", value).apply()

    /**
     * The button always sits just above the keyboard, and holding it talks
     * (let go to type). Off: it stays where it's dragged, and holding moves it.
     */
    var buttonFollowsKeyboard: Boolean
        get() = prefs.getBoolean("button_follows_keyboard", false)
        set(value) = prefs.edit().putBoolean("button_follows_keyboard", value).apply()

    /**
     * Transcribe while you talk (PLAN §4.3 live chunking). Off by default
     * since 2026-09-26: dictations with pauses came out with words missing on
     * the phone, while the same code was fine on test audio. Under diagnosis.
     */
    var liveChunking: Boolean
        get() = prefs.getBoolean("live_chunking", false)
        set(value) = prefs.edit().putBoolean("live_chunking", value).apply()

    /** Pause videos and music while listening; they resume after. */
    var pauseOtherAudio: Boolean
        get() = prefs.getBoolean("pause_other_audio", true)
        set(value) = prefs.edit().putBoolean("pause_other_audio", value).apply()

    /** Hold volume up to dictate a note (a tap still changes the volume). */
    var volumeNotes: Boolean
        get() = prefs.getBoolean("volume_notes", true)
        set(value) = prefs.edit().putBoolean("volume_notes", value).apply()
}
