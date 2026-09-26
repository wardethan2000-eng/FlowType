package com.ethanward.flowtype.ui

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.insert.InsertionLog
import com.ethanward.flowtype.insert.Outcome
import com.ethanward.flowtype.service.DictationService

/**
 * The insertion test (PLAN §7 Phase 0 step 4): fields of each kind to try the
 * button on here, a switch that makes the button type a fixed phrase so the
 * app matrix goes quickly, and the insertion log (app, input type, lengths,
 * outcome; never the text).
 */
class InsertionTestActivity : AppCompatActivity() {
    private lateinit var log: InsertionLog
    private lateinit var prefs: Prefs
    private lateinit var summary: TextView
    private lateinit var entries: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = InsertionLog(this)
        prefs = Prefs(this)
        page("Insertion test") {
            check("Button types \"${DictationService.TEST_PHRASE}\" instead of dictating (turns itself off after 30 minutes)", prefs.testPhraseMode).apply {
                setOnCheckedChangeListener { _, on -> prefs.testPhraseMode = on }
            }
            heading("Test fields")
            field("Single line")
            field("Several lines", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).minLines = 3
            text("WebView: a textarea and a rich-text (contenteditable) box")
            addView(
                WebView(context).apply {
                    // loadData would read "#" in the markup as a URL fragment and drop the rest.
                    loadDataWithBaseURL(null, WEB_FIELDS, "text/html", "utf-8", null)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)),
            )
            heading("Log")
            row {
                button("Refresh") { refresh() }
                button("Clear") { log.clear(); refresh() }
            }
            summary = text()
            entries = mono()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val records = log.recent(200)
        summary.text = if (records.isEmpty()) "No insertions yet." else
            records.groupBy { it.app }.entries.joinToString("\n") { (app, rs) ->
                val counts = Outcome.values().mapNotNull { o -> rs.count { it.outcome == o }.takeIf { it > 0 }?.let { "$o $it" } }
                "$app: ${counts.joinToString(", ")}"
            }
        entries.text = records.take(60).joinToString("\n") { it.describe() }
    }

    companion object {
        private const val WEB_FIELDS = """<!doctype html><meta name="viewport" content="width=device-width">
<body style="font:16px sans-serif;margin:8px">
<textarea style="width:100%;height:80px" placeholder="Textarea"></textarea>
<div contenteditable="true" style="border:1px solid #888;min-height:80px;margin-top:8px;padding:4px"></div>
</body>"""
    }
}
