package com.ethanward.flowtype.ui

import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.dictionary.Dictionary
import com.ethanward.flowtype.dictionary.DictionaryPass
import com.ethanward.flowtype.dictionary.DictionaryStore
import com.ethanward.flowtype.dictionary.Replacement
import com.ethanward.flowtype.dictionary.Snippet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.ethanward.flowtype.Prefs
import org.json.JSONObject

/**
 * The dictionary (PLAN §4.4): Words (exact spellings), Replacements
 * (what you say → what gets typed) and Snippets (a phrase → saved text), a box
 * to try them out, and import/export as a JSON file.
 */
class DictionaryActivity : AppCompatActivity() {
    private lateinit var store: DictionaryStore
    private lateinit var prefs: Prefs
    private var dict = Dictionary()
    private lateinit var words: LinearLayout
    private lateinit var replacements: LinearLayout
    private lateinit var snippets: LinearLayout
    private lateinit var tryIn: EditText
    private lateinit var tryOut: TextView

    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(dict.toJson().toString(2).toByteArray()) }
        }.onSuccess { toast("Dictionary exported") }.onFailure { toast("Couldn't write that file") }
    }

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Dictionary.fromJson(JSONObject(text))
        }.onSuccess { incoming ->
            save(dict.merge(incoming))
            toast("Added ${incoming.words.size} words, ${incoming.replacements.size} replacements and ${incoming.snippets.size} snippets")
        }.onFailure { toast("That isn't a Flowtype dictionary file") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DictionaryStore(this)
        prefs = Prefs(this)
        dict = store.load()
        page("Dictionary") {
            text("Flowtype uses these as it types, on the phone, with no internet needed.", secondary = true)
                .setPadding(dp(4), 0, dp(4), dp(12))

            section("Words")
            val wordCard = card(padded = false) { }
            words = wordCard.column
            card(padded = false) {
                addView(MaterialSwitch(context).apply {
                    text = "Also catch words that sound like them"
                    isChecked = prefs.soundsLike
                    setPadding(dp(16), dp(8), dp(16), 0)
                    setOnCheckedChangeListener { _, on ->
                        prefs.soundsLike = on
                        updateTry()
                    }
                })
                text("\"bamboo\" → \"Bambu\", \"deck all forge\" → \"DecalForge\". Short words only catch " +
                    "doubled letters (\"Allan\" → \"Alan\"); acronyms never change.", secondary = true).setPadding(dp(16), 0, dp(16), dp(12))
            }
            section("Replacements")
            val repCard = card(padded = false) { }
            replacements = repCard.column

            section("Snippets")
            val snippetCard = card(padded = false) { }
            snippets = snippetCard.column

            section("Try it")
            card {
                text("Type a sentence the way it might be heard, and see what Flowtype would type.", secondary = true)
                tryIn = field("e.g. the decal forge sign in pet g", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                tryOut = text()
                tryIn.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) = updateTry()
                })
            }

            section("Back up")
            card {
                text("A JSON file you can keep, edit, or bring to another phone. Importing adds to what's here.", secondary = true)
                row {
                    button("Export", ButtonKind.OUTLINED) { exporter.launch("flowtype-dictionary.json") }
                    button("Import", ButtonKind.OUTLINED) { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
                }
            }
        }
        render()
    }

    private fun save(d: Dictionary) {
        dict = d
        store.save(d)
        render()
    }

    private fun render() {
        words.removeAllViews()
        if (dict.words.isEmpty()) {
            words.hint("Names, brands and jargon, spelled exactly: DecalForge, PETG, Bambu. " +
                "Flowtype fixes their capitals, and joins CamelCase words heard apart (\"decal forge\").")
        }
        for (w in dict.words) {
            words.navRow(w) { editWord(w) }
            words.divider()
        }
        words.addAction("Add a word") { editWord(null) }

        replacements.removeAllViews()
        if (dict.replacements.isEmpty()) {
            replacements.hint("When the phone keeps hearing something wrong, say what it types and what it should type: " +
                "\"bamboo\" → \"Bambu\", \"pet g\" → \"PETG\".")
        }
        for (r in dict.replacements) {
            replacements.navRow("${r.from}  →  ${r.to}") { editReplacement(r) }
            replacements.divider()
        }
        replacements.addAction("Add a replacement") { editReplacement(null) }

        snippets.removeAllViews()
        if (dict.snippets.isEmpty()) {
            snippets.hint("Say a phrase, get saved text: \"my address\" types your whole address, exactly as saved. " +
                "AI cleanup never changes a snippet.")
        }
        for (sn in dict.snippets) {
            snippets.navRow(sn.trigger, sn.text.lineSequence().first().take(60)) { editSnippet(sn) }
            snippets.divider()
        }
        snippets.addAction("Add a snippet") { editSnippet(null) }
        updateTry()
    }

    private fun LinearLayout.hint(message: String) {
        text(message, secondary = true).setPadding(dp(16), dp(14), dp(16), dp(6))
    }

    private fun LinearLayout.addAction(label: String, onClick: () -> Unit) {
        val holder = LinearLayout(context).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        holder.button("+  $label", ButtonKind.TEXT) { onClick() }
        addView(holder)
    }

    private fun updateTry() {
        if (!::tryOut.isInitialized) return
        val input = tryIn.text?.toString().orEmpty()
        if (input.isBlank()) {
            tryOut.text = ""
            return
        }
        val pass = DictionaryPass(dict, prefs.soundsLike)
        tryOut.text = "→ ${pass.expand(pass.apply(input).text)}"
    }

    private fun editWord(existing: String?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val field = form.field("Spelled exactly as it should be typed", existing.orEmpty())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add a word" else "Edit word")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> save(dict.withoutWord(existing)) } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = field.text.toString()
                val problem = Dictionary.problemWithWord(text)
                if (problem != null) {
                    field.error = problem
                    return@setOnClickListener
                }
                save(dict.withWord(text, replacing = existing))
                dialog.dismiss()
            }
        }
        dialog.show()
        field.requestFocus()
    }

    private fun editReplacement(existing: Replacement?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val from = form.field("When it hears", existing?.from.orEmpty())
        val to = form.field("Type", existing?.to.orEmpty())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add a replacement" else "Edit replacement")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> save(dict.withoutReplacement(existing)) } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val problem = Dictionary.problemWithReplacement(from.text.toString(), to.text.toString())
                if (problem != null) {
                    (if (from.text.isNullOrBlank() || problem.startsWith("Type what you say")) from else to).error = problem
                    return@setOnClickListener
                }
                save(dict.withReplacement(Replacement(from.text.toString(), to.text.toString()), replacing = existing))
                dialog.dismiss()
            }
        }
        dialog.show()
        from.requestFocus()
    }

    private fun editSnippet(existing: Snippet?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val trigger = form.field("When I say", existing?.trigger.orEmpty())
        val text = form.field(
            "Type this", existing?.text.orEmpty(),
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        ).apply { minLines = 3 }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add a snippet" else "Edit snippet")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete") { _, _ -> save(dict.withoutSnippet(existing)) } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val problem = Dictionary.problemWithSnippet(trigger.text.toString(), text.text.toString())
                if (problem != null) {
                    (if (problem.startsWith("Type what you'll say")) trigger else text).error = problem
                    return@setOnClickListener
                }
                save(dict.withSnippet(Snippet(trigger.text.toString(), text.text.toString()), replacing = existing))
                dialog.dismiss()
            }
        }
        dialog.show()
        trigger.requestFocus()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
