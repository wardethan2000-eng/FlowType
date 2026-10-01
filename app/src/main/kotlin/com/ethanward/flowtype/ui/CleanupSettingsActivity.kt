package com.ethanward.flowtype.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.cleanup.ApiKeyStore
import com.ethanward.flowtype.cleanup.Cleaner
import com.ethanward.flowtype.cleanup.CleanupConfig
import com.ethanward.flowtype.cleanup.CleanupSetup
import com.ethanward.flowtype.cleanup.ModelList
import com.ethanward.flowtype.cleanup.Prices
import com.ethanward.flowtype.cleanup.Protocol
import com.ethanward.flowtype.cleanup.ProviderPreset
import com.ethanward.flowtype.cleanup.Providers
import com.ethanward.flowtype.cleanup.UsageStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/**
 * AI cleanup settings (PLAN §4.9, LAUNCH §A2): whose AI, its key (paste,
 * save, which tests, test, remove), its model, the cost, the wait. Each
 * provider keeps its own key in [ApiKeyStore], only ever shown masked.
 */
class CleanupSettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var preset: ProviderPreset
    private lateinit var keys: ApiKeyStore
    private lateinit var keyStatus: TextView
    private lateinit var keyField: EditText
    private lateinit var testResult: TextView
    private lateinit var costs: TextView
    private var modelText: TextView? = null
    private var serverField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        preset = Providers.byId(prefs.cleanupProvider)
        keys = ApiKeyStore(this, preset.id)
        page("AI cleanup") {
            text(
                "After the phone turns your speech into text, an AI tidies it: drops the ums, keeps " +
                    "your final version when you correct yourself, and fixes punctuation. Only the text is " +
                    "sent, never your voice. If it's slow or fails, Flowtype types the phone's own text.",
                secondary = true,
            ).setPadding(dp(4), 0, dp(4), dp(12))

            card(padded = false) {
                addView(MaterialSwitch(context).apply {
                    text = "Use AI cleanup"
                    textSize = 16f
                    isChecked = prefs.cleanupEnabled
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnCheckedChangeListener { _, on -> prefs.cleanupEnabled = on }
                })
            }

            section("Whose AI")
            card {
                val group = RadioGroup(context)
                for (p in Providers.ALL) {
                    group.addView(RadioButton(context).apply {
                        id = View.generateViewId()
                        text = "${p.label}\n${p.blurb}"
                        isChecked = p.id == preset.id
                        setPadding(dp(8), dp(6), 0, dp(6))
                        setOnCheckedChangeListener { _, on ->
                            if (on && p.id != preset.id) {
                                prefs.cleanupProvider = p.id
                                prefs.keyProblem = null
                                recreate()
                            }
                        }
                    })
                }
                addView(group)
            }

            if (preset.baseUrl == null) {
                section("Server address")
                card {
                    text("Where your server listens. For Ollama on your computer that's its address and port, " +
                        "such as 192.168.1.5:11434; the phone and computer need to be on the same Wi-Fi.", secondary = true)
                    serverField = field("Address", prefs.customServer.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                    button("Save address", ButtonKind.TONAL) { saveServer() }
                }
            }

            section(if (preset.keyOptional) "${preset.label} key (if it needs one)" else "${preset.label} key")
            card {
                keyStatus = text()
                keyField = field("Paste your key", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                row {
                    button("Paste", ButtonKind.TONAL) { pasteKey() }
                    button("Save", ButtonKind.FILLED) { saveKey() }
                }
                row {
                    button("Test", ButtonKind.OUTLINED) { test() }
                    button("Remove", ButtonKind.OUTLINED) { confirmRemove() }
                }
                testResult = text()
                text(
                    "Your key stays on this phone, encrypted, and is only ever sent to " +
                        (if (preset.baseUrl == null) "your server." else "${preset.label}."),
                    secondary = true,
                )
                preset.keyUrl?.let { url -> button("Get a key", ButtonKind.TEXT) { open(url) } }
                if (preset.id == Providers.OPENAI.id) {
                    button("Set a spend limit", ButtonKind.TEXT) { open("https://platform.openai.com/settings/organization/limits") }
                }
            }

            section("Model")
            card {
                if (preset.protocol == Protocol.OPENAI_RESPONSES) openAiModels() else {
                    modelText = text()
                    row {
                        button("Choose", ButtonKind.TONAL) { chooseModel() }
                        button("Type it", ButtonKind.OUTLINED) { typeModel() }
                    }
                }
            }

            section("What it costs")
            card {
                costs = text()
                text("Worked out from the tokens each request used and the provider's list prices, so it's " +
                    "an estimate; their bill is the final word. Models whose prices Flowtype doesn't know are " +
                    "counted in tokens.", secondary = true)
            }

            section("Wait for cleanup")
            card {
                text("If the answer takes longer than this, Flowtype types the phone's own text.", secondary = true)
                val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
                for ((ms, label) in listOf(1800L to "1.8 s", 3000L to "3 s", 5000L to "5 s")) {
                    group.addView(RadioButton(context).apply {
                        id = View.generateViewId()
                        text = label
                        isChecked = prefs.cleanupDeadlineMs == ms
                        setPadding(dp(4), 0, dp(16), 0)
                        setOnCheckedChangeListener { _, on -> if (on) prefs.cleanupDeadlineMs = ms }
                    })
                }
                addView(group)
            }
        }
        showKey()
        showModel()
    }

    override fun onResume() {
        super.onResume()
        showCosts()
    }

    /** OpenAI's models come with tier settings, so they're a fixed list. */
    private fun android.widget.LinearLayout.openAiModels() {
        val group = RadioGroup(context)
        val descriptions = mapOf(
            CleanupConfig.LUNA.id to "Careful and cheapest, but can be slow to answer.",
            CleanupConfig.LUNA_FAST.id to "Recommended. Luna on OpenAI's fast tier: quicker, twice the price.",
            CleanupConfig.NANO.id to "Older and quick; a little less careful.",
        )
        for (c in CleanupConfig.ALL) {
            group.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = "${c.label}\n${descriptions[c.id]}"
                isChecked = prefs.cleanupModel == c.id
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, on -> if (on) prefs.cleanupModel = c.id }
            })
        }
        addView(group)
    }

    private fun setup() = CleanupSetup.current(this, prefs)

    private fun showCosts() {
        val s = UsageStore(this).summary()
        val inTokens = !Prices.known(setup().config)
        fun line(label: String, t: UsageStore.Totals): String {
            val amount = if (inTokens) "%,d tokens".format(t.tokens) else Prices.format(t.dollars)
            return "$label: $amount" + if (t.calls > 0) " (${t.calls} request${if (t.calls == 1) "" else "s"})" else ""
        }
        costs.text = listOf(line("Today", s.today), line("Last 7 days", s.week), line("This month", s.month)).joinToString("\n")
    }

    private fun showKey() {
        val key = keys.load()
        keyStatus.text = when {
            key != null && key.isNotEmpty() -> "Saved: ${ApiKeyStore.mask(key)}" + (prefs.keyProblem?.let { " · " + problemText(it) } ?: "")
            keys.has() && key == null -> "A key is saved but can't be read. Remove it and paste it again."
            preset.keyOptional -> "No key saved. Most servers on your own computer don't need one."
            else -> "No key yet. Flowtype works without one; you just don't get AI cleanup."
        }
    }

    private fun showModel() {
        val text = modelText ?: return
        val model = setup().config.model
        text.text = when {
            model.isEmpty() -> "None picked yet. Save your key, then Choose."
            model == preset.suggestedModel && prefs.providerModel(preset.id) == null -> "$model (suggested)"
            else -> model
        }
    }

    private fun saveServer() {
        val typed = serverField?.text?.toString()?.trim().orEmpty()
        if (Providers.baseUrl(preset, typed) == null) {
            testResult.text = "Type your server's address first."
            return
        }
        prefs.customServer = typed
        testResult.text = "Saved: ${Providers.baseUrl(preset, typed)}"
    }

    private fun pasteKey() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        keyField.setText(pasted?.let(ApiKeyStore::normalize).orEmpty())
    }

    private fun saveKey() {
        val key = ApiKeyStore.normalize(keyField.text.toString())
        Providers.problemWithKey(preset, key)?.let {
            testResult.text = it
            return
        }
        if (key.isEmpty()) {
            testResult.text = "Paste a key first."
            return
        }
        keys.save(key)
        keyField.setText("")
        prefs.keyProblem = null
        showKey()
        test()
    }

    /** A tiny real cleanup with the saved key and model. */
    private fun test() {
        val s = setup()
        val key = s.keyToSend ?: run {
            testResult.text = "Save a key first."
            return
        }
        if (s.baseUrl == null) {
            testResult.text = "Save your server's address first."
            return
        }
        if (s.config.model.isEmpty()) {
            testResult.text = "Choose a model first."
            return
        }
        testResult.text = "Testing ${s.modelLabel}…"
        thread(name = "flowtype-key-test") {
            val result = Cleaner({ key }, UsageStore(this), s.provider()).test(key, s.config)
            runOnUiThread {
                testResult.text = when (result) {
                    is Cleaner.Result.Cleaned -> {
                        prefs.keyProblem = null
                        "✓ Working. The answer took ${result.totalMs} ms."
                    }
                    is Cleaner.Result.Fallback -> {
                        if (result.reason.keyProblem) prefs.keyProblem = result.reason.name
                        failure(result.reason, result.http, s.modelLabel)
                    }
                }
                showKey()
                showCosts()
            }
        }
    }

    private fun failure(reason: Cleaner.Reason, http: Int, model: String): String = when (reason) {
        Cleaner.Reason.KEY_REJECTED -> "✗ ${preset.label} didn't accept this key."
        Cleaner.Reason.NO_CREDIT -> "✗ This account has no credit left."
        Cleaner.Reason.MODEL_UNAVAILABLE -> "✗ This key can't use $model. Pick another model or check the key."
        Cleaner.Reason.OFFLINE -> if (preset.baseUrl == null) "✗ Can't reach your server. Is it running, and on the same Wi-Fi?" else "✗ No internet."
        Cleaner.Reason.DEADLINE -> "✗ No answer within 15 seconds."
        Cleaner.Reason.RATE_LIMITED -> "✗ Too many requests right now. Try again in a minute."
        Cleaner.Reason.TOO_LONG, Cleaner.Reason.TOO_SHORT, Cleaner.Reason.ASSISTANT, Cleaner.Reason.EMPTY ->
            "✓ The key works (the test answer was a bit odd, which is fine)."
        else -> "✗ ${preset.label} returned an error" + (if (http > 0) " (HTTP $http)." else ".")
    }

    /** The provider's own list of models, with ours suggested. */
    private fun chooseModel() {
        val s = setup()
        val key = s.keyToSend
        if (key == null || s.baseUrl == null) {
            testResult.text = if (s.baseUrl == null) "Save your server's address first." else "Save a key first."
            return
        }
        testResult.text = "Asking ${preset.label} for its models…"
        thread(name = "flowtype-models") {
            val answer = ModelList.fetch(preset, s.baseUrl, key)
            runOnUiThread {
                when (answer) {
                    is ModelList.Answer.Failed -> testResult.text = failure(answer.reason, answer.http, "this model")
                    is ModelList.Answer.Models -> {
                        testResult.text = ""
                        if (answer.ids.isEmpty()) {
                            testResult.text = "${preset.label} listed no models. Use Type it."
                            return@runOnUiThread
                        }
                        val ids = preset.suggestedModel?.takeIf { it in answer.ids }
                            ?.let { listOf(it) + (answer.ids - it) } ?: answer.ids
                        val labels = ids.map { if (it == preset.suggestedModel) "$it (suggested)" else it }.toTypedArray()
                        MaterialAlertDialogBuilder(this)
                            .setTitle("${preset.label} models")
                            .setItems(labels) { _, i -> pickModel(ids[i]) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun typeModel() {
        val input = EditText(this).apply {
            setText(setup().config.model)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Model id")
            .setView(input)
            .setPositiveButton("Use it") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::pickModel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickModel(model: String) {
        prefs.setProviderModel(preset.id, model)
        prefs.keyProblem = null
        showModel()
        showCosts()
    }

    private fun confirmRemove() {
        if (!keys.has()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove your ${preset.label} key?")
            .setMessage("Flowtype will keep working, without AI cleanup from ${preset.label}.")
            .setPositiveButton("Remove") { _, _ ->
                keys.remove()
                prefs.keyProblem = null
                testResult.text = ""
                showKey()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    companion object {
        fun problemText(reason: String) = when (reason) {
            Cleaner.Reason.KEY_REJECTED.name -> "not accepted"
            Cleaner.Reason.NO_CREDIT.name -> "no credit"
            Cleaner.Reason.MODEL_UNAVAILABLE.name -> "can't use this model"
            else -> "problem"
        }
    }
}
