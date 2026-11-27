package com.ethanward.flowtype.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.ethanward.flowtype.Prefs
import com.ethanward.flowtype.cleanup.AppStyle
import com.ethanward.flowtype.cleanup.TextingStyle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/**
 * Punctuation by app: how texts are punctuated ([TextingStyle]), and which
 * kind of app each app counts as ([AppStyle]), for apps Flowtype doesn't know
 * or gets wrong.
 */
class WritingStyleActivity : AppCompatActivity() {
    private class App(val packageName: String, val label: String)

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private var apps: List<App> = emptyList()
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page("Punctuation") {
            text(
                "Flowtype punctuates to suit the app you're in: relaxed in a chat, full sentences in an " +
                    "email or a note, and nothing extra in a search box.",
                secondary = true,
            ).setPadding(dp(4), 0, dp(4), dp(12))

            section("In texts")
            card {
                val group = RadioGroup(context)
                val current = TextingStyle.byId(prefs.textingStyle)
                for (style in TextingStyle.entries) {
                    group.addView(RadioButton(context).apply {
                        id = View.generateViewId()
                        text = "${style.label}\n${style.example}"
                        isChecked = style == current
                        setPadding(dp(8), dp(8), 0, dp(8))
                        setOnCheckedChangeListener { _, on -> if (on) prefs.textingStyle = style.id }
                    })
                }
                addView(group)
                text("Question marks and exclamation points always stay.", secondary = true)
            }

            section("Apps")
            text(
                "Flowtype knows the common chat, email and notes apps. Tap any app to say what it is.",
                secondary = true,
            ).setPadding(dp(4), 0, dp(4), dp(8))
            field("Find an app").addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString()?.trim().orEmpty()
                    render()
                }
            })
            card(padded = false) { list = this }
        }
        list.text("Looking for your apps…", secondary = true).setPadding(dp(16), dp(14), dp(16), dp(14))
        thread(name = "flowtype-apps") {
            val found = installedApps()
            runOnUiThread {
                apps = found
                render()
            }
        }
    }

    /** Apps with a launcher icon, by name. */
    private fun installedApps(): List<App> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .map { pkg ->
                    val label = runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
                    }.getOrDefault(pkg.substringAfterLast('.'))
                    App(pkg, label)
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** The apps that aren't "everything else" first, then the rest, each by name. */
    private fun render() {
        val picked = prefs.appStyles()
        fun style(app: App) = picked[app.packageName] ?: AppStyle.builtIn(app.packageName)
        val shown = apps.filter { it.label.contains(filter, ignoreCase = true) }
            .sortedBy { style(it) == AppStyle.GENERAL }
        list.removeAllViews()
        if (shown.isEmpty()) {
            list.text(if (apps.isEmpty()) "No apps found." else "No app with that name.", secondary = true)
                .setPadding(dp(16), dp(14), dp(16), dp(14))
            return
        }
        for ((i, app) in shown.withIndex()) {
            if (i > 0) list.divider()
            val yours = if (app.packageName in picked) " · your pick" else ""
            list.navRow(app.label, AppStyle.label(style(app)) + yours) { choose(app) }
        }
    }

    private fun choose(app: App) {
        val builtIn = AppStyle.builtIn(app.packageName)
        val picked = prefs.appStyles()[app.packageName]
        // First the way back to Flowtype's own choice, then the four kinds.
        val styles: List<String?> = listOf<String?>(null) + AppStyle.PICKABLE.map { it.first }
        val labels = styles.map { it?.let(AppStyle::label) ?: "Let Flowtype decide (${AppStyle.label(builtIn)})" }
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setSingleChoiceItems(labels.toTypedArray(), styles.indexOf(picked)) { dialog, which ->
                prefs.setAppStyle(app.packageName, styles[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
