package com.ethanward.flowtype.insert

import android.accessibilityservice.InputMethod
import android.os.SystemClock
import com.ethanward.flowtype.Trace

/**
 * Inserts through the accessibility service's own input connection
 * (PLAN §4.6 step 1): commitText at the cursor, as a keyboard would, then
 * getSurroundingText to confirm it landed. Phase 0 stops there; the SET_TEXT
 * and paste fallbacks come in Phase 1.
 *
 * Call off the main thread: getSurroundingText blocks on the target app.
 */
class Inserter(private val inputMethod: InputMethod) {

    data class Result(val outcome: Outcome, val checkMs: Long, val retries: Int)

    fun insert(text: String): Result {
        if (text.isBlank()) return Result(Outcome.EMPTY, 0, 0)
        val ic = inputMethod.currentInputConnection ?: return Result(Outcome.NO_CONNECTION, 0, 0)

        val before = ic.getSurroundingText(1, 0, 0)
        val charBefore = before?.let {
            val cursor = it.selectionStart // relative to the returned text
            if (cursor > 0 && cursor <= it.text.length) it.text[cursor - 1] else null
        }
        val toInsert = InsertionRules.withLeadingSpace(text, charBefore)

        ic.commitText(toInsert, 1, null)

        // commitText reports nothing; ask the app what is now before the cursor.
        // Some apps apply the edit a frame later, hence the short retries.
        val started = SystemClock.elapsedRealtime()
        var retries = 0
        var landed: Boolean? = null
        for (delay in RETRY_DELAYS_MS) {
            if (delay > 0) {
                Thread.sleep(delay)
                retries++
            }
            val after = ic.getSurroundingText(toInsert.length, 0, 0) ?: break
            val cursor = after.selectionStart
            val beforeCursor = if (cursor in 0..after.text.length) after.text.subSequence(0, cursor) else null
            landed = InsertionRules.landed(beforeCursor, toInsert)
            if (landed == true) break
        }
        val checkMs = SystemClock.elapsedRealtime() - started
        val outcome = when (landed) {
            true -> Outcome.VERIFIED
            false -> Outcome.NOT_VERIFIED
            null -> Outcome.UNCHECKED
        }
        Trace.event("insert", "outcome" to outcome, "chars" to toInsert.length, "checkMs" to checkMs, "retries" to retries)
        return Result(outcome, checkMs, retries)
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(0, 30, 100, 250)
    }
}
