package com.ethanward.flowtype.history

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One dictation (PLAN §4.7): what the phone heard (after the dictionary),
 * what was typed, where, and how it went. Kept on the phone only, never logged.
 */
data class HistoryEntry(
    val at: Long,
    val app: String,
    /** The phone's own text. */
    val raw: String,
    /** What was typed: the cleaned text, or [raw] when cleanup didn't run or failed. */
    val typed: String,
    /** "cleaned", or why not (a Cleaner.Reason name, "off"). */
    val cleanup: String,
    /** The insertion outcome. */
    val outcome: String,
    val stopToTypedMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("app", app).put("raw", raw).put("typed", typed)
        .put("cleanup", cleanup).put("outcome", outcome).put("ms", stopToTypedMs)

    companion object {
        fun fromJson(j: JSONObject) = HistoryEntry(
            j.getLong("at"), j.optString("app"), j.optString("raw"), j.optString("typed"),
            j.optString("cleanup"), j.optString("outcome"), j.optLong("ms"),
        )

        const val MAX = 50

        /** The newest [MAX] entries younger than [days] days, oldest first. */
        fun prune(entries: List<HistoryEntry>, days: Int, now: Long): List<HistoryEntry> {
            if (days <= 0) return emptyList()
            val cutoff = now - days * 86_400_000L
            return entries.filter { it.at >= cutoff }.sortedBy { it.at }.takeLast(MAX)
        }
    }
}

/** history.jsonl in the app's private storage. */
class HistoryStore(context: Context, private val days: () -> Int) {
    private val file = File(context.filesDir, "history.jsonl")

    @Synchronized
    fun add(e: HistoryEntry) {
        if (days() <= 0) return
        write(HistoryEntry.prune(read() + e, days(), System.currentTimeMillis()))
    }

    /** Newest first. */
    @Synchronized
    fun list(): List<HistoryEntry> {
        val kept = HistoryEntry.prune(read(), days(), System.currentTimeMillis())
        return kept.reversed()
    }

    /** Applies the retention now (after it's shortened, for instance). */
    @Synchronized
    fun prune() = write(HistoryEntry.prune(read(), days(), System.currentTimeMillis()))

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): List<HistoryEntry> =
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { runCatching { HistoryEntry.fromJson(JSONObject(it)) }.getOrNull() }

    private fun write(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(entries.joinToString("") { it.toJson().toString() + "\n" })
        tmp.renameTo(file)
    }
}
