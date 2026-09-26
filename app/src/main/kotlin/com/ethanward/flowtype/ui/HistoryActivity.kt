package com.ethanward.flowtype.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.history.HistoryEntry
import com.ethanward.flowtype.history.HistoryStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The last 50 dictations, searchable, kept on the phone for a chosen time (PLAN §4.7). */
class HistoryActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: HistoryStore
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private val labels = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = HistoryStore(this) { prefs.historyDays }
        page("History") {
            card {
                text("Keep my dictations for")
                val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
                for ((days, label) in listOf(0 to "Off", 1 to "1 day", 7 to "7 days", 30 to "30 days")) {
                    group.addView(RadioButton(context).apply {
                        id = View.generateViewId()
                        text = label
                        isChecked = prefs.historyDays == days
                        setPadding(dp(2), 0, dp(12), 0)
                        setOnCheckedChangeListener { _, on ->
                            if (!on) return@setOnCheckedChangeListener
                            prefs.historyDays = days
                            store.prune()
                            render()
                        }
                    })
                }
                addView(group)
                text("Only on this phone, the newest 50. Never uploaded or logged.", secondary = true)
            }
            search = field("Search")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
            list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
            button("Delete all", ButtonKind.OUTLINED) { confirmClear() }
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val q = search.text?.toString()?.trim().orEmpty()
        val entries = store.list().filter {
            q.isEmpty() || it.typed.contains(q, ignoreCase = true) || it.raw.contains(q, ignoreCase = true)
        }
        if (entries.isEmpty()) {
            list.text(
                when {
                    prefs.historyDays == 0 -> "History is off."
                    q.isNotEmpty() -> "Nothing matches."
                    else -> "Nothing yet. Your dictations will show up here."
                },
                secondary = true,
            ).setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        for (e in entries) list.entry(e)
    }

    private fun LinearLayout.entry(e: HistoryEntry) = card {
        val time = DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val how = when (e.cleanup) {
            "cleaned" -> "cleaned up"
            "off", "NO_KEY", "SHORT" -> null
            "DEADLINE" -> "cleanup too slow"
            "OFFLINE" -> "offline"
            else -> "not cleaned"
        }
        text(listOfNotNull(time, appLabel(e.app), how).joinToString(" · "), secondary = true)
        text(e.typed).apply {
            setTextIsSelectable(true)
            textSize = 16f
        }
        if (e.raw != e.typed) text("Heard: ${e.raw}", secondary = true).setTextIsSelectable(true)
        row {
            button("Copy", ButtonKind.TEXT) { copy(e.typed) }
            if (e.raw != e.typed) button("Copy original", ButtonKind.TEXT) { copy(e.raw) }
        }
    }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flowtype", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete all history?")
            .setPositiveButton("Delete") { _, _ ->
                store.clear()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
