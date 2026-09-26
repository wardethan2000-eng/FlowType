package com.ethanward.flowtype.insert

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Outcome {
    /** getSurroundingText shows our text right before the cursor. */
    VERIFIED,
    /** The app returned surrounding text, and ours isn't at the cursor. */
    NOT_VERIFIED,
    /** Committed, but the app gave no surrounding text to check against. */
    UNCHECKED,
    /** No input connection (the field went away). Nothing sent. */
    NO_CONNECTION,
    /** A different field or app took input since recording started. Nothing sent. */
    FIELD_CHANGED,
    /** The model heard nothing. Nothing sent. */
    EMPTY,
    /** commitText did nothing; ACTION_SET_TEXT under the §4.6 rules worked, checked. */
    SET_TEXT,
    /** commitText did nothing and SET_TEXT wasn't allowed; pasted from the clipboard. */
    PASTED,
    /** Nothing else worked: the text is on the clipboard for a long-press paste. */
    COPIED,
}

/**
 * The insertion log behind the insertion-test screen: one line per attempt with
 * the app, the field's input type, lengths, timings and the outcome. Never the
 * text (AGENTS.md).
 */
data class InsertionRecord(
    val at: Long,
    val app: String,
    val inputType: Int,
    val source: String,
    val chars: Int,
    val outcome: Outcome,
    val checkMs: Long,
    val retries: Int,
) {
    fun toLine() = listOf(at, app, "0x%x".format(inputType), source, chars, outcome, checkMs, retries).joinToString("\t")

    fun describe(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
        return "$time  $outcome  $app  type=${"0x%x".format(inputType)}  $source  chars=$chars  check=${checkMs}ms retries=$retries"
    }

    companion object {
        fun parse(line: String): InsertionRecord? {
            val f = line.split('\t')
            if (f.size != 8) return null
            return runCatching {
                InsertionRecord(
                    at = f[0].toLong(), app = f[1], inputType = f[2].removePrefix("0x").toInt(16),
                    source = f[3], chars = f[4].toInt(), outcome = Outcome.valueOf(f[5]),
                    checkMs = f[6].toLong(), retries = f[7].toInt(),
                )
            }.getOrNull()
        }
    }
}

class InsertionLog(context: Context) {
    private val file = File(context.filesDir, "insertion-log.tsv")

    @Synchronized
    fun add(record: InsertionRecord) {
        file.appendText(record.toLine() + "\n")
    }

    @Synchronized
    fun recent(limit: Int = 100): List<InsertionRecord> =
        if (!file.exists()) emptyList()
        else file.readLines().takeLast(limit).mapNotNull(InsertionRecord::parse).reversed()

    @Synchronized
    fun clear() {
        file.delete()
    }
}
