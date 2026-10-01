package com.ethanward.flowtype.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.View
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.R
import com.ethanward.flowtype.asr.AsrModels
import com.ethanward.flowtype.asr.Downloads
import com.ethanward.flowtype.asr.ModelStore
import com.ethanward.flowtype.cleanup.ApiKeyStore
import com.ethanward.flowtype.cleanup.CleanupConfig
import com.ethanward.flowtype.cleanup.Prices
import com.ethanward.flowtype.cleanup.UsageStore
import com.ethanward.flowtype.dictionary.DictionaryStore
import com.ethanward.flowtype.service.DictationService
import com.google.android.material.R as M
import com.google.android.material.card.MaterialCardView

/**
 * Home: a status card that says "Ready" or lists what's missing with a button
 * for each, then the settings.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var status: MaterialCardView
    private lateinit var dictionarySummary: TextView
    private lateinit var modelSummary: TextView
    private lateinit var cleanupSummary: TextView
    private lateinit var historySummary: TextView
    private lateinit var buttonSummary: TextView
    private lateinit var tryCard: MaterialCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = ModelStore(this)
        page("Flowtype", up = false) {
            status = card { }
            tryCard = card {
                heading("Try it here")
                field("Tap here, then the mic above the keyboard", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            }
            section("Settings")
            card(padded = false) {
                cleanupSummary = navRow("AI cleanup") { open(CleanupSettingsActivity::class.java) }
                divider()
                dictionarySummary = navRow("Dictionary") { open(DictionaryActivity::class.java) }
                divider()
                historySummary = navRow("History") { open(HistoryActivity::class.java) }
                divider()
                modelSummary = navRow("Speech model") { open(ModelsActivity::class.java) }
                divider()
                buttonSummary = navRow("Mic button") { open(ButtonSettingsActivity::class.java) }
            }
            section("Developer")
            card(padded = false) {
                navRow("Developer tools", "Speech bench, insertion test, cleanup timing") { open(DeveloperActivity::class.java) }
            }
            text("Speech is turned into text on this phone. Your voice never leaves it.", secondary = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), 0)
            }
        }
    }

    private val onDownload: () -> Unit = { refresh() }

    override fun onResume() {
        super.onResume()
        Downloads.listen(onDownload)
        refresh()
    }

    override fun onPause() {
        Downloads.unlisten(onDownload)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun open(screen: Class<*>) = startActivity(Intent(this, screen))

    private fun refresh() {
        val model = AsrModels.byId(prefs.modelId) ?: AsrModels.DEFAULT
        modelSummary.text = model.label + if (store.isInstalled(model)) "" else " · not downloaded"
        val d = DictionaryStore(this).load()
        dictionarySummary.text = if (d.isEmpty) "Names and words Flowtype should spell your way"
        else listOf(
            d.words.size.takeIf { it > 0 }?.let { "$it word" + if (it == 1) "" else "s" },
            d.replacements.size.takeIf { it > 0 }?.let { "$it replacement" + if (it == 1) "" else "s" },
        ).filterNotNull().joinToString(" · ")
        buttonSummary.text = if (prefs.buttonFollowsKeyboard) "Follows the keyboard · hold to talk"
        else "Stays where you put it · hold to move"
        historySummary.text = when (val d = prefs.historyDays) {
            0 -> "Off"
            1 -> "Your dictations, kept 1 day on this phone"
            else -> "Your dictations, kept $d days on this phone"
        }
        val keys = ApiKeyStore(this)
        cleanupSummary.text = when {
            !prefs.cleanupEnabled -> "Off"
            !keys.has() -> "Add your OpenAI key to turn it on"
            prefs.keyProblem != null -> "Key problem: " + CleanupSettingsActivity.problemText(prefs.keyProblem!!)
            else -> "On · " + CleanupConfig.byId(prefs.cleanupModel).label +
                " · " + Prices.format(UsageStore(this).summary().week.dollars) + " this week"
        }
        renderStatus(model.label, store.isInstalled(model))
    }

    private fun renderStatus(modelLabel: String, modelReady: Boolean) {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it)?.className == DictationService::class.java.name } == true
        val connected = DictationService.instance != null
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val todo = buildList {
            if (!enabled) add("Turn on Flowtype in Accessibility settings." to ("Open settings" to ::openAccessibility))
            else if (!connected) add("Flowtype is on but not running. Turn it off and on again." to ("Open settings" to ::openAccessibility))
            if (!mic) add("Allow Flowtype to use the microphone." to ("Allow" to ::askForMic))
            if (!modelReady) add("Download the speech model ($modelLabel)." to ("Models" to { open(ModelsActivity::class.java) }))
            else if (!store.isVadInstalled()) {
                val busy = "vad" in Downloads.running
                add((if (busy) "Downloading the pause detector…" else
                    "Download the pause detector (0.6 MB), so Flowtype transcribes while you talk.") to
                    ("Download" to { Downloads.start("vad") { report -> store.installVad(report) } }))
            }
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                add("Let Flowtype run in the background, so Samsung doesn't put it to sleep." to ("Allow" to ::askForBattery))
            }
        }

        val column = status.column
        column.removeAllViews()
        val ready = todo.isEmpty()
        tryCard.visibility = if (ready) View.VISIBLE else View.GONE
        status.setCardBackgroundColor(themeColor(if (ready) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh))
        val onColor = themeColor(if (ready) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurface)
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (ready) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check_circle)
                imageTintList = ColorStateList.valueOf(onColor)
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(TextView(context).apply {
                text = if (ready) "Ready" else "Finish setting up"
                setTextAppearance(M.style.TextAppearance_Material3_TitleLarge)
                setTextColor(onColor)
            })
        })
        if (ready) {
            column.text("Tap the mic above your keyboard in any app, speak, then tap ✓.").setTextColor(onColor)
            return
        }
        for ((message, action) in todo) {
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(TextView(context).apply {
                    text = message
                    setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val b = com.google.android.material.button.MaterialButton(context, null, R.attr.tonalButtonStyle).apply {
                    text = action.first
                    setOnClickListener { action.second() }
                }
                addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(12)
                })
            })
        }
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun askForMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

    /** Battery → Unrestricted, in one system prompt (PLAN §4.8 step 3). */
    @SuppressLint("BatteryLife")
    private fun askForBattery() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
    )
}
