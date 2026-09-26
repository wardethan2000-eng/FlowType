package com.ethanward.flowtype.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.asr.AsrModel
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.asr.Downloads
import com.ethanward.flowtype.asr.ModelStore
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Download, choose and delete the speech models. */
class ModelsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var list: LinearLayout
    private val onChange: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = ModelStore(this)
        page("Speech model") {
            text("Downloaded once, then everything runs on this phone, even with no signal.", secondary = true)
                .setPadding(dp(4), 0, dp(4), dp(12))
            list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
        }
    }

    override fun onStart() {
        super.onStart()
        Downloads.listen(onChange)
        render()
    }

    override fun onStop() {
        Downloads.unlisten(onChange)
        super.onStop()
    }

    private fun render() {
        list.removeAllViews()
        for (m in AsrModels.ALL) list.modelCard(m)
        list.section("Pause detector")
        list.card {
            heading("Silero VAD")
            text("Finds the pauses in your speech, so Flowtype can transcribe while you talk.", secondary = true)
            addView(com.google.android.material.materialswitch.MaterialSwitch(context).apply {
                text = "Transcribe while I talk (testing)"
                isChecked = prefs.liveChunking
                setOnCheckedChangeListener { _, on -> prefs.liveChunking = on }
            })
            text("Off: everything is transcribed after you tap ✓, a little slower but reliable. " +
                "On: quicker, but dictations with pauses are losing words right now.", secondary = true)
            progressAndActions("vad", store.isVadInstalled(), AsrModels.VAD_BYTES) {
                if (!store.isVadInstalled()) button("Download", ButtonKind.TONAL) {
                    Downloads.start("vad") { report -> store.installVad(report) }
                }
            }
        }
        if (Downloads.running.isNotEmpty()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun LinearLayout.modelCard(m: AsrModel) = card {
        val installed = store.isInstalled(m)
        val selected = prefs.modelId == m.id
        heading(m.label + if (selected) " · in use" else "")
        text(m.summary, secondary = true)
        progressAndActions(m.id, installed, m.bytes) {
            if (!installed) {
                button("Download", ButtonKind.TONAL) {
                    Downloads.start(m.id) { report -> store.install(m, report) }
                    if (!store.isVadInstalled()) Downloads.start("vad") { report -> store.installVad(report) }
                }
            } else {
                button("Use this one", ButtonKind.TONAL) {
                    prefs.modelId = m.id
                    render()
                }.isEnabled = !selected
                button("Delete", ButtonKind.OUTLINED) {
                    store.delete(m)
                    if (selected) prefs.modelId = AsrModels.DEFAULT.id
                    render()
                }
            }
        }
    }

    /** Size and state, a progress bar while downloading, then the buttons. */
    private fun LinearLayout.progressAndActions(key: String, installed: Boolean, bytes: Long, actions: LinearLayout.() -> Unit) {
        val progress = Downloads.running[key]
        val size = if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else "${bytes / 1000} KB"
        text(
            when {
                progress?.fraction == null && progress != null -> "$size · unpacking…"
                progress != null -> "$size · downloading, ${(progress.fraction!! * 100).toInt()}%"
                installed -> "$size · downloaded"
                else -> "$size download"
            } + (Downloads.errors[key]?.let { " · last try failed: $it" } ?: ""),
            secondary = true,
        )
        if (progress != null) {
            addView(LinearProgressIndicator(context).apply {
                isIndeterminate = progress.fraction == null
                progress.fraction?.let { setProgressCompat((it * 100).toInt(), false) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            return
        }
        val buttons = row(actions)
        buttons.setPadding(0, dp(8), 0, 0)
        if (buttons.childCount == 0) buttons.visibility = View.GONE
    }
}
