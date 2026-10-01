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
import com.ethanward.flowtype.cleanup.Prices
import com.ethanward.flowtype.cleanup.UsageStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/**
 * AI cleanup settings and the OpenAI key screen (PLAN §4.9): paste, save
 * (which tests), test, remove. The key is kept by [ApiKeyStore] and only ever
 * shown masked.
 */
class CleanupSettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var keys: ApiKeyStore
    private lateinit var keyStatus: TextView
    private lateinit var keyField: EditText
    private lateinit var testResult: TextView
    private val cleaner by lazy { Cleaner(keys::load, UsageStore(this)) }
    private lateinit var costs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        keys = ApiKeyStore(this)
        page("AI cleanup") {
            text(
                "After the phone turns your speech into text, OpenAI tidies it: drops the ums, keeps " +
                    "your final version when you correct yourself, and fixes punctuation. Only the text is " +
                    "sent, never your voice, and OpenAI doesn't keep it. If it's slow or fails, Flowtype " +
                    "types the phone's own text.",
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

            section("What it costs")
            card {
                costs = text()
                text("Worked out from the tokens each request used and OpenAI's list prices, so it's an " +
                    "estimate; your OpenAI bill is the final word.", secondary = true)
            }

            section("OpenAI API key")
            card {
                keyStatus = text()
                keyField = field("Paste your key (sk-…)", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                row {
                    button("Paste", ButtonKind.TONAL) { pasteKey() }
                    button("Save", ButtonKind.FILLED) { saveKey() }
                }
                row {
                    button("Test key", ButtonKind.OUTLINED) { testKey() }
                    button("Remove", ButtonKind.OUTLINED) { confirmRemove() }
                }
                testResult = text()
                text("Your key stays on this phone, encrypted, and is only ever sent to OpenAI. " +
                    "Heavy use costs about \$0.20–0.50 a month.", secondary = true)
                row {
                    button("Create a key", ButtonKind.TEXT) { open("https://platform.openai.com/api-keys") }
                    button("Set a spend limit", ButtonKind.TEXT) { open("https://platform.openai.com/settings/organization/limits") }
                }
            }

            section("Model")
            card {
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
    }

    override fun onResume() {
        super.onResume()
        showCosts()
    }

    private fun showCosts() {
        val s = UsageStore(this).summary()
        fun line(label: String, t: UsageStore.Totals) =
            "$label: ${Prices.format(t.dollars)}" + if (t.calls > 0) " (${t.calls} request${if (t.calls == 1) "" else "s"})" else ""
        costs.text = listOf(line("Today", s.today), line("Last 7 days", s.week), line("This month", s.month)).joinToString("\n")
    }

    private fun showKey() {
        val key = keys.load()
        keyStatus.text = when {
            key != null -> "Saved: ${ApiKeyStore.mask(key)}" + (prefs.keyProblem?.let { " · " + problemText(it) } ?: "")
            keys.has() -> "A key is saved but can't be read. Remove it and paste it again."
            else -> "No key yet. Flowtype works without one; you just don't get AI cleanup."
        }
    }

    private fun pasteKey() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        keyField.setText(pasted?.let(ApiKeyStore::normalize).orEmpty())
    }

    private fun saveKey() {
        val key = ApiKeyStore.normalize(keyField.text.toString())
        if (!ApiKeyStore.looksValid(key)) {
            testResult.text = "That doesn't look like an OpenAI key. It starts with sk-."
            return
        }
        keys.save(key)
        keyField.setText("")
        prefs.keyProblem = null
        showKey()
        testKey()
    }

    private fun testKey() {
        val key = keys.load() ?: run {
            testResult.text = "Save a key first."
            return
        }
        val config = CleanupConfig.byId(prefs.cleanupModel)
        testResult.text = "Testing with ${config.label}…"
        thread(name = "flowtype-key-test") {
            val result = cleaner.test(key, config)
            runOnUiThread {
                testResult.text = when (result) {
                    is Cleaner.Result.Cleaned -> {
                        prefs.keyProblem = null
                        "✓ Working. The answer took ${result.totalMs} ms."
                    }
                    is Cleaner.Result.Fallback -> {
                        if (result.reason.keyProblem) prefs.keyProblem = result.reason.name
                        when (result.reason) {
                            Cleaner.Reason.KEY_REJECTED -> "✗ OpenAI didn't accept this key."
                            Cleaner.Reason.NO_CREDIT -> "✗ This account has no credit. Add some at platform.openai.com."
                            Cleaner.Reason.MODEL_UNAVAILABLE -> "✗ This key can't use ${config.label}. Pick another model or check the key's project."
                            Cleaner.Reason.OFFLINE -> "✗ No internet."
                            Cleaner.Reason.DEADLINE -> "✗ No answer within 15 seconds."
                            Cleaner.Reason.RATE_LIMITED -> "✗ Too many requests right now. Try again in a minute."
                            Cleaner.Reason.TOO_LONG, Cleaner.Reason.TOO_SHORT, Cleaner.Reason.ASSISTANT, Cleaner.Reason.EMPTY ->
                                "✓ The key works (the test answer was a bit odd, which is fine)."
                            else -> "✗ OpenAI returned an error" + (if (result.http > 0) " (HTTP ${result.http})." else ".")
                        }
                    }
                }
                showKey()
                showCosts()
            }
        }
    }

    private fun confirmRemove() {
        if (!keys.has()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove your key?")
            .setMessage("Flowtype will keep working, without AI cleanup.")
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
