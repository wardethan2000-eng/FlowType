package com.ethanward.flowtype.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.asr.AsrModel
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.asr.ModelStore
import com.ethanward.flowtype.service.DictationService
import kotlin.concurrent.thread

/** Setup and status: service, microphone, speech models, developer screens. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var health: TextView
    private lateinit var models: LinearLayout
    private val progress = HashMap<String, String>()
    private var downloads = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = ModelStore(this)
        page("Flowtype") {
            health = text()
            row {
                button("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                button("Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
            }
            heading("Speech models")
            text("Downloaded over the internet once, then everything runs on this phone.")
            models = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(models)
            heading("Developer")
            button("ASR bench") { startActivity(Intent(this@MainActivity, BenchActivity::class.java)) }
            button("Insertion test") { startActivity(Intent(this@MainActivity, InsertionTestActivity::class.java)) }
            button("Cleanup timing") { startActivity(Intent(this@MainActivity, CleanupTestActivity::class.java)) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it)?.className == DictationService::class.java.name } == true
        val connected = DictationService.instance != null
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        health.text = listOf(
            "Service: " + when {
                connected -> "on"
                enabled -> "turned on, not connected yet"
                else -> "off (turn on Flowtype in Accessibility settings)"
            },
            "Microphone: " + if (mic) "allowed" else "not allowed",
            "Dictation model: ${model.label}" + if (store.isInstalled(model)) "" else " (not downloaded)",
        ).joinToString("\n")
        renderModels()
    }

    private fun renderModels() {
        models.removeAllViews()
        for (m in AsrModels.ALL) {
            val installed = store.isInstalled(m)
            val selected = prefs.modelId == m.id
            models.text("${m.label}\n${m.bytes / 1_000_000} MB download · " +
                (progress[m.id] ?: if (installed) "downloaded" else "not downloaded") +
                if (selected) " · used for dictation" else "")
            models.row {
                if (!installed) button("Download") { install(m) }.isEnabled = progress[m.id] == null
                else {
                    button("Use for dictation") { prefs.modelId = m.id; refresh() }.isEnabled = !selected
                    button("Delete") { store.delete(m); refresh() }
                }
            }
        }
        val vad = if (store.isVadInstalled()) "downloaded" else progress["vad"] ?: "not downloaded"
        models.text("Silero VAD (for the bench's chunked decoding) · $vad")
        if (!store.isVadInstalled() && progress["vad"] == null) {
            models.button("Download VAD") { installVad() }
        }
    }

    private fun install(m: AsrModel) = background(m.id) { report -> store.install(m, report) }

    private fun installVad() = background("vad") { report -> store.installVad(report) }

    private fun background(key: String, work: ((Long, Long) -> Unit) -> Unit) {
        progress[key] = "starting…"
        keepScreenOn(+1)
        renderModels()
        thread(name = "flowtype-download") {
            val result = runCatching {
                work { done, total ->
                    val text = if (done < 0) "unpacking…" else "${done * 100 / total}% downloaded"
                    runOnUiThread { progress[key] = text; renderModels() }
                }
            }
            runOnUiThread {
                progress.remove(key)
                keepScreenOn(-1)
                refresh()
                result.exceptionOrNull()?.let { health.append("\nDownload failed: ${it.message}") }
            }
        }
    }

    private fun keepScreenOn(delta: Int) {
        downloads += delta
        if (downloads > 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
