package com.ethanward.flowtype.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A spoken note: its text, the phone's own text before cleanup, and a title
 * (the AI's, or the first few words until that arrives).
 */
data class Note(val id: Long, val at: Long, val text: String, val raw: String, val title: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("text", text).put("raw", raw)
        .put("title", title)

    companion object {
        fun fromJson(j: JSONObject) = Note(
            j.getLong("id"), j.getLong("at"), j.optString("text"), j.optString("raw"), j.optString("title"),
        )
    }
}

/**
 * notes.json in the app's private storage. Notes are kept until deleted
 * (unlike history, they're something you chose to keep). Never uploaded.
 */
class NotesStore(context: Context) {
    private val file = File(context.filesDir, "notes.json")

    /** Newest first. */
    @Synchronized
    fun list(): List<Note> = read().sortedByDescending { it.at }

    @Synchronized
    fun add(text: String, raw: String, title: String): Note {
        val now = System.currentTimeMillis()
        val note = Note(now, now, text, raw, title)
        write(read() + note)
        return note
    }

    @Synchronized
    fun setTitle(id: Long, title: String) = write(read().map { if (it.id == id) it.copy(title = title) else it })

    @Synchronized
    fun delete(id: Long) = write(read().filterNot { it.id == id })

    private fun read(): List<Note> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText())
            List(a.length()) { Note.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(notes: List<Note>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(notes.map { it.toJson() }).toString())
        tmp.renameTo(file)
    }
}
