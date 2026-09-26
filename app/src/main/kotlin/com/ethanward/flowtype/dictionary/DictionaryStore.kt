package com.ethanward.flowtype.dictionary

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The dictionary file, dictionary.json in the app's private storage: the same
 * format as import/export. It's a few kilobytes, so the service rereads it on
 * every dictation and edits apply at once; parsing is skipped when the text
 * hasn't changed.
 */
class DictionaryStore(context: Context) {
    private val file = File(context.filesDir, "dictionary.json")
    private var cached: Pair<String, Dictionary>? = null

    @Synchronized
    fun load(): Dictionary {
        val text = if (file.exists()) file.readText() else ""
        cached?.let { (t, d) -> if (t == text) return d }
        val d = if (text.isEmpty()) Dictionary() else runCatching { Dictionary.fromJson(JSONObject(text)) }.getOrDefault(Dictionary())
        cached = text to d
        return d
    }

    @Synchronized
    fun save(d: Dictionary) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(d.toJson().toString(2))
        tmp.renameTo(file)
        cached = null
    }
}
