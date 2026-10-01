package com.ethanward.flowtype.ui

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.cleanup.ApiKeyStore
import com.ethanward.flowtype.cleanup.AppStyle
import com.ethanward.flowtype.cleanup.Cleaner
import com.ethanward.flowtype.cleanup.CleanupConfig
import com.ethanward.flowtype.cleanup.CleanupPrompt
import com.ethanward.flowtype.cleanup.CleanupTiming
import com.ethanward.flowtype.cleanup.UsageStore
import com.ethanward.flowtype.dictionary.DictionaryStore
import com.ethanward.flowtype.history.HistoryStore
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Cleanup timing from the phone (PLAN §7 Phase 0 step 6). It uses the key
 * saved on the AI cleanup screen.
 *
 * Numbers append to `cleanup/results.jsonl` in the app's external files folder:
 * no key, no text.
 *
 * Replay history sends your saved dictations through today's prompt and model,
 * to judge a prompt change on real speech. Its answers hold your text, so they
 * go to `replay.jsonl` in the app's private storage (like the history itself),
 * and only counts are shown.
 */
class CleanupTestActivity : AppCompatActivity() {
    private lateinit var keys: ApiKeyStore
    private lateinit var keyStatus: TextView
    private lateinit var runs: EditText
    private lateinit var pause: EditText
    private lateinit var output: TextView
    private val configChecks = LinkedHashMap<CleanupConfig, CheckBox>()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = ApiKeyStore(this)
        page("Cleanup timing") {
            heading("Key")
            keyStatus = text()
            button("Change it in AI cleanup", ButtonKind.OUTLINED) {
                startActivity(android.content.Intent(this@CleanupTestActivity, CleanupSettingsActivity::class.java))
            }
            heading("Run")
            text("Sends made-up dictations (nothing of yours) with a ${CleanupPrompt.estimateTokens(CleanupPrompt.instructions(CleanupTiming.DICTIONARY))}-token prefix, estimated. store: false.")
            for (c in CleanupConfig.ALL) configChecks[c] = check("${c.label} (${c.model}" + (c.serviceTier?.let { ", tier $it" } ?: "") + ")", true)
            runs = field("Requests per model (the first on a new connection)", "5", InputType.TYPE_CLASS_NUMBER)
            pause = field("Pause between requests, ms", "1500", InputType.TYPE_CLASS_NUMBER)
            button("Run timing") { run() }
            heading("Replay history")
            text("Cleans every saved dictation again with today's prompt and model (one request each, a fraction of a cent in all). Answers stay on the phone.", secondary = true)
            button("Replay history", ButtonKind.OUTLINED) { replay() }
            output = mono()
        }
    }

    override fun onResume() {
        super.onResume()
        keyStatus.text = keys.load()?.let { "Using ${ApiKeyStore.mask(it)}" } ?: "No key saved."
    }

    private fun log(line: String) = runOnUiThread { output.append(line + "\n") }

    private fun run() {
        if (running) return
        val key = keys.load() ?: return log("Save a key first.")
        val configs = configChecks.filter { it.value.isChecked }.keys.toList()
        val count = runs.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 5
        val pauseMs = pause.text.toString().toLongOrNull()?.coerceIn(0, 60_000) ?: 1500
        val results = File(getExternalFilesDir(null), "cleanup/results.jsonl").apply { parentFile?.mkdirs() }
        running = true
        output.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread(name = "flowtype-cleanup-timing") {
            val batch = System.currentTimeMillis()
            try {
                val timing = CleanupTiming(key, com.ethanward.flowtype.cleanup.UsageStore(this@CleanupTestActivity))
                for (c in configs) {
                    log("== ${c.label}")
                    var last = ""
                    timing.measure(c, count, pauseMs) { r ->
                        log(r.describe())
                        last = r.output
                        results.appendText(r.toJson().put("batch", batch).put("promptVersion", CleanupPrompt.VERSION).toString() + "\n")
                    }
                    // Made-up input, so showing the model's answer is fine: it's how a
                    // wrong model id or an "answered instead of cleaned" shows up.
                    log("last output: $last")
                }
                log("Done. Results: ${results.path}")
            } catch (e: Throwable) {
                log("Failed: ${e.javaClass.simpleName}")
            } finally {
                running = false
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private fun replay() {
        if (running) return
        val prefs = Prefs(this)
        val setup = com.ethanward.flowtype.cleanup.CleanupSetup.current(this, prefs)
        if (!setup.ready) return log("Set up ${setup.preset.label} in AI cleanup first.")
        val entries = HistoryStore(this) { prefs.historyDays }.list().reversed()
        val dict = DictionaryStore(this).load()
        val words = dict.words + dict.replacements.map { it.to }.filter { t -> t.any(Char::isUpperCase) }
        val config = setup.config
        val out = File(filesDir, "replay.jsonl")
        running = true
        output.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread(name = "flowtype-cleanup-replay") {
            try {
                val cleaner = Cleaner({ setup.keyToSend }, UsageStore(this@CleanupTestActivity), setup.provider())
                val lines = StringBuilder()
                val outcomes = LinkedHashMap<String, Int>()
                log("== ${entries.size} dictations, ${setup.preset.label} ${config.label}, prompt v${CleanupPrompt.VERSION}")
                for ((i, e) in entries.withIndex()) {
                    // History has no field type: the app's style, as a plain text field.
                    val style = AppStyle.forField(e.app, android.text.InputType.TYPE_CLASS_TEXT, 0) ?: AppStyle.GENERAL
                    val r = cleaner.clean(e.raw, style, words, config, 15_000)
                    val (outcome, text, ms) = when (r) {
                        is Cleaner.Result.Cleaned -> Triple("cleaned", r.text, r.totalMs)
                        is Cleaner.Result.Fallback -> Triple(r.reason.name, null, r.totalMs)
                    }
                    outcomes.merge(outcome, 1, Int::plus)
                    lines.append(
                        JSONObject().put("at", e.at).put("app", e.app).put("style", style).put("raw", e.raw)
                            .put("before", e.typed).put("beforeOutcome", e.cleanup)
                            .put("after", text ?: JSONObject.NULL).put("afterOutcome", outcome).put("ms", ms)
                            .put("promptVersion", CleanupPrompt.VERSION).toString(),
                    ).append('\n')
                    log("#$i $outcome ${ms} ms")
                }
                out.writeText(lines.toString())
                log("Done: " + outcomes.entries.joinToString { "${it.key} ${it.value}" })
            } catch (e: Throwable) {
                log("Failed: ${e.javaClass.simpleName}")
            } finally {
                running = false
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }
}
