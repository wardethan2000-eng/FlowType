package com.ethanward.flowtype.insert

import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.ethanward.flowtype.Trace

/**
 * Types text at the cursor, in the order PLAN §4.6 sets:
 *
 * 1. commitText on the service's own input connection, as a keyboard would,
 *    then getSurroundingText to confirm it landed.
 * 2. Only if that provably changed nothing: ACTION_SET_TEXT, when the rules in
 *    [InsertionRules.setTextSplice] say it can't destroy anything.
 * 3. Paste from the clipboard (marked sensitive).
 * 4. Leave it on the clipboard and say so.
 *
 * Field text is read into memory for these checks and never logged.
 * Call off the main thread: getSurroundingText blocks on the target app.
 */
class Inserter(
    private val inputMethod: InputMethod,
    private val focusedField: () -> AccessibilityNodeInfo?,
    private val clipboard: ClipboardManager,
) {

    data class Result(val outcome: Outcome, val checkMs: Long, val retries: Int)

    fun insert(text: String): Result {
        if (text.isBlank()) return Result(Outcome.EMPTY, 0, 0)
        val started = SystemClock.elapsedRealtime()
        val node = focusedField()
        val nodeBefore = node?.text?.toString()
        val ic = inputMethod.currentInputConnection

        var retries = 0
        // Enough context to see all of our text before the cursor afterwards.
        val context = maxOf(CONTEXT, text.length + 2)
        if (ic != null) {
            val before = ic.getSurroundingText(context, 0, 0)
            val beforeCursor = before?.let { textBeforeCursor(it.text, it.selectionStart) }
            val toInsert = InsertionRules.withLeadingSpace(text, beforeCursor?.lastOrNull())
            ic.commitText(toInsert, 1, null)

            // commitText reports nothing; ask the app what is now before the
            // cursor. Some apps apply the edit a frame later, hence the retries.
            var afterCursor: CharSequence? = null
            var landed: Boolean? = null
            for (delay in RETRY_DELAYS_MS) {
                if (delay > 0) {
                    Thread.sleep(delay)
                    retries++
                }
                val after = ic.getSurroundingText(context, 0, 0) ?: break
                afterCursor = textBeforeCursor(after.text, after.selectionStart)
                landed = InsertionRules.landed(afterCursor, toInsert)
                if (landed == true) break
            }
            if (landed == true) return done(Outcome.VERIFIED, started, retries, toInsert.length)

            // Did the commit do anything at all? Look once more after a pause,
            // so a slow app's late edit isn't mistaken for "nothing happened".
            Thread.sleep(SETTLE_MS)
            val cursorSame = InsertionRules.unchanged(beforeCursor, ic.getSurroundingText(context, 0, 0)?.let {
                textBeforeCursor(it.text, it.selectionStart)
            })
            node?.refresh()
            val nodeSame = beforeCursor == null && InsertionRules.unchanged(nodeBefore, node?.text)
            if (!cursorSame && !nodeSame) {
                // Something changed but we can't see our text: don't risk typing it twice.
                return done(if (landed == false) Outcome.NOT_VERIFIED else Outcome.UNCHECKED, started, retries, toInsert.length)
            }
        }

        // Step 2: SET_TEXT, only when it can't destroy anything.
        if (node != null && node.isEditable) {
            val splice = InsertionRules.setTextSplice(
                node.text, node.isShowingHintText, node.hintText,
                node.textSelectionStart, node.textSelectionEnd, text,
            )
            if (splice != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, splice.text)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, splice.cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, splice.cursor)
                    })
                    node.refresh()
                    if (node.text?.toString() == splice.text) return done(Outcome.SET_TEXT, started, retries, text.length)
                }
            }
        }

        // Steps 3 and 4: the clipboard. Marked sensitive so its preview is hidden.
        clipboard.setPrimaryClip(ClipData.newPlainText("Flowtype", text.trim()).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        })
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            Thread.sleep(SETTLE_MS)
            node.refresh()
            if (!InsertionRules.unchanged(nodeBefore, node.text)) return done(Outcome.PASTED, started, retries, text.length)
        }
        return done(if (ic == null && node == null) Outcome.NO_CONNECTION else Outcome.COPIED, started, retries, text.length)
    }

    private fun done(outcome: Outcome, started: Long, retries: Int, chars: Int): Result {
        val ms = SystemClock.elapsedRealtime() - started
        Trace.event("insert", "outcome" to outcome, "chars" to chars, "checkMs" to ms, "retries" to retries)
        return Result(outcome, ms, retries)
    }

    private fun textBeforeCursor(text: CharSequence, selectionStart: Int): CharSequence? =
        if (selectionStart in 0..text.length) text.subSequence(0, selectionStart) else null

    companion object {
        /** Characters read before the cursor for the checks, at least. */
        private const val CONTEXT = 64
        private val RETRY_DELAYS_MS = longArrayOf(0, 30, 100, 250)
        private const val SETTLE_MS = 300L
    }
}
