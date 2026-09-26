package com.ethanward.flowtype.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.cleanup.NoteTitler
import com.ethanward.flowtype.notes.Note
import com.ethanward.flowtype.notes.NotesStore
import com.ethanward.flowtype.service.DictationService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Voice notes (PLAN §4.11): double-press volume up anywhere, speak, and the
 * note lands here. Each one can go to Google Keep in one tap (Keep has no API
 * for personal accounts, so Keep's own save card does the saving).
 */
class NotesActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: NotesStore
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = NotesStore(this)
        page("Notes") {
            card(padded = false) {
                addView(MaterialSwitch(context).apply {
                    text = "Double-press volume up to take a note"
                    isChecked = prefs.volumeNotes
                    setPadding(dp(16), dp(8), dp(16), 0)
                    setOnCheckedChangeListener { _, on ->
                        prefs.volumeNotes = on
                        showStatus()
                    }
                })
                status = text(secondary = true).apply { setPadding(dp(16), 0, dp(16), dp(4)) }
                val holder = LinearLayout(context).apply { setPadding(dp(8), 0, dp(8), dp(4)) }
                holder.button("Take a note now", ButtonKind.TEXT) {
                    DictationService.instance?.startNote()
                        ?: Toast.makeText(this@NotesActivity, "Turn on Flowtype in Accessibility settings first", Toast.LENGTH_SHORT).show()
                }
                addView(holder)
            }
            search = field("Search notes")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
            list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
        }
    }

    // The AI title lands a moment after a note is saved: refresh while it's on screen.
    private val poll = object : Runnable {
        override fun run() {
            render()
            list.postDelayed(this, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        showStatus()
        render()
        list.postDelayed(poll, 3000)
    }

    override fun onPause() {
        list.removeCallbacks(poll)
        super.onPause()
    }

    private fun showStatus() {
        val service = DictationService.instance
        status.text = when {
            !prefs.volumeNotes -> "Off. You can still take a note with the button below."
            service == null -> "Flowtype's service is off. Turn it on in Accessibility settings."
            !service.filtersKeys -> "Android isn't passing volume keys to Flowtype yet. Turn Flowtype off and on " +
                "again in Accessibility settings."
            else -> "Works whenever the screen is on, even on the lock screen. Press volume up again, or tap ✓, " +
                "to save; ✕ throws it away."
        }
        if (service != null && prefs.volumeNotes && !service.filtersKeys) {
            status.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    private fun render() {
        list.removeAllViews()
        val q = search.text?.toString()?.trim().orEmpty()
        val notes = store.list().filter {
            q.isEmpty() || it.text.contains(q, ignoreCase = true) || it.title.contains(q, ignoreCase = true)
        }
        if (notes.isEmpty()) {
            list.text(if (q.isEmpty()) "No notes yet." else "Nothing matches.", secondary = true)
                .setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        for (n in notes) list.note(n)
    }

    private fun LinearLayout.note(n: Note) = card {
        heading(n.title.ifEmpty { NoteTitler.fallback(n.text) })
        text(DateUtils.getRelativeTimeSpanString(n.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), secondary = true)
        text(n.text).apply {
            setTextIsSelectable(true)
            textSize = 16f
        }
        row {
            button("Send to Keep", ButtonKind.TONAL) { sendToKeep(n.title.ifEmpty { NoteTitler.fallback(n.text) }, n.text) }
            button("Copy", ButtonKind.TEXT) { copy(n.text) }
            button("Delete", ButtonKind.TEXT) { confirmDelete(n) }
        }
    }

    /** Keep's own share card, filled in; one tap on Save there. Any app if Keep isn't installed. */
    private fun sendToKeep(title: String, text: String) {
        // Keep takes the subject as the note's title.
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        val keep = Intent(send).setPackage(KEEP)
        if (keep.resolveActivity(packageManager) != null) startActivity(keep)
        else startActivity(Intent.createChooser(send, "Send note"))
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flowtype note", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(n: Note) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this note?")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(n.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val KEEP = "com.google.android.keep"
    }
}
