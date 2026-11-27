package com.ethanward.flowtype.desktop

import com.ethanward.flowtype.Trace
import com.ethanward.flowtype.asr.SpokenCommands
import com.ethanward.flowtype.asr.dropGluedOkay
import com.ethanward.flowtype.cleanup.Cleaner
import com.ethanward.flowtype.cleanup.CleanupConfig
import com.ethanward.flowtype.cleanup.Protocol
import com.ethanward.flowtype.cleanup.Providers
import com.ethanward.flowtype.cleanup.Styles
import com.ethanward.flowtype.cleanup.TextingStyle
import com.ethanward.flowtype.dictionary.Dictionary
import com.ethanward.flowtype.dictionary.DictionaryPass
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * flowtype-clean: the phone's text pipeline for voxtype's post-process hook
 * (PLAN §7 Phase 5). Voxtype sends what Parakeet heard on stdin and types
 * what this prints. Every failure prints the local text, so dictation never
 * stops working because of the network or the key, as on the phone (§4.5).
 *
 * Settings: `~/.config/flowtype/desktop.json` (all optional, see [Settings]).
 * Dictionary: `~/.config/flowtype/dictionary.json`, the phone's export format.
 * Key: KDE Wallet via `secret-tool lookup service flowtype provider <id>`, or
 * FLOWTYPE_API_KEY. Logs lengths and timings to stderr, never text or the key.
 */
fun main(args: Array<String>) {
    Trace.sink = { level, line -> System.err.println("flowtype ${level.name.lowercase()} $line") }
    val dir = File(System.getenv("FLOWTYPE_CONFIG_DIR") ?: "${System.getProperty("user.home")}/.config/flowtype")
    val settings = Settings.load(File(dir, "desktop.json"))
    val raw = System.`in`.readBytes().toString(Charsets.UTF_8).trim()
    val effective = if ("--no-ai" in args) settings.copy(cleanup = false) else settings
    val started = System.currentTimeMillis()
    val desktop = Desktop(
        effective,
        Desktop.loadDictionary(File(dir, "dictionary.json")),
        Desktop::key,
        Desktop::notify,
        WindowStyle::queryKWin,
    )
    val out = runCatching { desktop.run(raw) }.getOrElse {
        Trace.warn("desktop_failed", "error" to it.javaClass.simpleName)
        desktop.outcome = "error"
        raw
    }
    if (settings.history && raw.isNotEmpty()) {
        History.append(History.file(), raw, desktop.local, out, desktop.outcome, desktop.resolvedStyle, System.currentTimeMillis() - started)
    }
    // No newline: voxtype types every character, and a newline is Enter.
    print(out)
    System.out.flush()
    // OkHttp's threads would keep the JVM up for a minute after a call.
    exitProcess(0)
}

/** desktop.json. The defaults match the phone's (Prefs), with Claude as the provider. */
data class Settings(
    val provider: String = Providers.ANTHROPIC.id,
    /** Null: the provider's suggested model (Haiku 5.5 for Claude). For OpenAI, a CleanupConfig id. */
    val model: String? = null,
    /** "Your own server": its address, as typed on the phone. */
    val server: String? = null,
    /** "auto" detects from the active KWin window (falling back to "general"), or pin one of Styles. */
    val style: String = WindowStyle.AUTO,
    val textingStyle: String = TextingStyle.DEFAULT.id,
    /** Per-app/tab style overrides (window class/name or browser title substring → style). */
    val appStyles: Map<String, String> = emptyMap(),
    val deadlineMs: Long = 3000,
    val soundsLike: Boolean = true,
    /** False: the local pass only, no AI call. */
    val cleanup: Boolean = true,
    /** Keep what was heard and typed in ~/.local/state/flowtype/history.jsonl, as the phone does. */
    val history: Boolean = true,
) {
    companion object {
        fun load(file: File): Settings {
            if (!file.exists()) return Settings()
            return runCatching { parse(JSONObject(file.readText())) }.getOrElse {
                Trace.warn("settings_unreadable", "error" to it.javaClass.simpleName)
                Settings()
            }
        }

        fun parse(j: JSONObject): Settings {
            val d = Settings()
            val styles = buildMap {
                val obj = j.optJSONObject("appStyles")
                if (obj != null) {
                    for (k in obj.keys()) {
                        val v = obj.optString(k)
                        if (v.isNotEmpty()) put(k, v)
                    }
                }
            }
            return Settings(
                provider = j.optString("provider", d.provider),
                model = j.optString("model").takeIf { it.isNotEmpty() },
                server = j.optString("server").takeIf { it.isNotEmpty() },
                style = j.optString("style", d.style),
                textingStyle = j.optString("textingStyle", d.textingStyle),
                appStyles = styles,
                deadlineMs = j.optLong("deadlineMs", d.deadlineMs),
                soundsLike = j.optBoolean("soundsLike", d.soundsLike),
                cleanup = j.optBoolean("cleanup", d.cleanup),
                history = j.optBoolean("history", d.history),
            )
        }
    }
}

/** The phone's order (DictationService): okay fix → spoken commands → dictionary → cleanup → dictionary again → texting style → snippets. */
class Desktop(
    private val settings: Settings,
    private val dictionary: Dictionary,
    /** The key for a provider id, or null for none. */
    private val key: (String) -> String?,
    /** Surface key/billing failures to the desktop so cleanup never fails silently. */
    private val notify: (String, String) -> Unit = { _, _ -> },
    /** Active desktop window for "auto" style detection; null falls back to general. */
    private val activeWindow: () -> ActiveWindow? = { null },
) {
    /** After [run]: the text before cleanup, what cleanup did ("cleaned", a fallback reason, "off"), and the style used. */
    var local: String = ""
    var outcome: String = "off"
    var resolvedStyle: String = Styles.GENERAL

    private fun resolveStyle(): String =
        if (settings.style.equals(WindowStyle.AUTO, ignoreCase = true)) {
            WindowStyle.forWindow(activeWindow(), settings.appStyles)
        } else {
            settings.style
        }

    private fun texting(text: String, style: String): String =
        if (style == Styles.MESSAGING && text.isNotBlank()) TextingStyle.byId(settings.textingStyle).apply(text) else text

    fun run(raw: String): String {
        val started = System.nanoTime()
        val pass = DictionaryPass(dictionary, settings.soundsLike)
        val local = pass.apply(SpokenCommands.apply(dropGluedOkay(raw))).text
        this.local = local
        if (local.isBlank()) return pass.expand(local)
        val style = resolveStyle()
        resolvedStyle = style
        if (!settings.cleanup) {
            outcome = "off"
            return pass.expand(texting(local, style))
        }

        val preset = Providers.byId(settings.provider)
        val baseUrl = Providers.baseUrl(preset, settings.server)
        val config = if (preset.protocol == Protocol.OPENAI_RESPONSES) CleanupConfig.byId(settings.model)
        else CleanupConfig.of(preset, settings.model ?: preset.suggestedModel.orEmpty())
        if (baseUrl == null || config.model.isEmpty()) {
            Trace.event("cleanup", "outcome" to "not_set_up", "provider" to preset.id)
            return pass.expand(texting(local, style))
        }
        val words = dictionary.words + dictionary.replacements.map { it.to }.filter { t -> t.any(Char::isUpperCase) }
        val result = Cleaner({ key(preset.id) ?: if (preset.keyOptional) "" else null }, null, Providers.provider(preset, baseUrl))
            .clean(local, style, words, config, settings.deadlineMs, null)
        outcome = when (result) {
            is Cleaner.Result.Cleaned -> "cleaned"
            is Cleaner.Result.Fallback -> result.reason.name
        }
        val text = when (result) {
            is Cleaner.Result.Cleaned -> {
                Trace.event(
                    "cleanup", "outcome" to "cleaned", "model" to config.id, "style" to style,
                    "firstTokenMs" to result.firstTokenMs, "totalMs" to result.totalMs,
                    "charsIn" to local.length, "charsOut" to result.text.length,
                )
                // Pass 2: the model may have re-cased a dictionary word.
                texting(pass.apply(result.text).text, style)
            }
            is Cleaner.Result.Fallback -> {
                Trace.event(
                    "cleanup", "outcome" to result.reason, "model" to config.id, "style" to style,
                    "totalMs" to result.totalMs, "http" to result.http, "charsIn" to local.length,
                )
                if (result.reason.keyProblem) {
                    notify("Flowtype AI cleanup failed", keyProblemMessage(result.reason, preset.label))
                }
                texting(local, style)
            }
        }
        Trace.event("desktop", "style" to style, "ms" to (System.nanoTime() - started) / 1_000_000)
        return pass.expand(text)
    }

    companion object {
        fun keyProblemMessage(reason: Cleaner.Reason, provider: String) = when (reason) {
            Cleaner.Reason.KEY_REJECTED -> "$provider didn't accept your key, so this was typed without AI cleanup"
            Cleaner.Reason.NO_CREDIT -> "Your $provider account is out of credit, so this was typed without AI cleanup"
            else -> "Your key can't use the cleanup model, so this was typed without AI cleanup"
        }

        fun notify(title: String, body: String) {
            runCatching {
                val p = ProcessBuilder(
                    "notify-send", "-a", "Flowtype", "-u", "normal",
                    "-h", "string:x-canonical-private-synchronous:flowtype-key",
                    title, body,
                ).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        fun loadDictionary(file: File): Dictionary {
            if (!file.exists()) return Dictionary()
            return runCatching { Dictionary.fromJson(JSONObject(file.readText())) }.getOrElse {
                Trace.warn("dictionary_unreadable", "error" to it.javaClass.simpleName)
                Dictionary()
            }
        }

        /** FLOWTYPE_API_KEY, else the key saved in the desktop's secret store (KDE Wallet). */
        fun key(provider: String): String? {
            System.getenv("FLOWTYPE_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            return runCatching {
                val p = ProcessBuilder("secret-tool", "lookup", "service", "flowtype", "provider", provider)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    Trace.warn("key_lookup", "outcome" to "timeout")
                    return null
                }
                p.inputStream.readBytes().toString(Charsets.UTF_8).trim().takeIf { p.exitValue() == 0 && it.isNotEmpty() }
            }.getOrNull()
        }
    }
}

/**
 * The laptop's dictation history: one JSON line per dictation with what
 * Parakeet heard, the text before cleanup and what was typed. Private to the
 * user (0600), on this machine only, the last [KEEP] kept. It holds dictation
 * text, so it never leaves the laptop and is read only when Ethan asks.
 */
object History {
    const val KEEP = 300

    fun file() = File(System.getenv("XDG_STATE_HOME") ?: "${System.getProperty("user.home")}/.local/state", "flowtype/history.jsonl")

    fun append(file: File, heard: String, local: String, typed: String, outcome: String, style: String, ms: Long) {
        runCatching {
            file.parentFile.mkdirs()
            val line = JSONObject()
                .put("at", java.time.Instant.now().toString())
                .put("heard", heard).put("local", local).put("typed", typed)
                .put("outcome", outcome).put("style", style).put("ms", ms)
                .toString()
            val lines = (if (file.exists()) file.readLines() else emptyList()) + line
            val tmp = File(file.path + ".tmp")
            tmp.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            tmp.renameTo(file)
        }.onFailure { Trace.warn("history_failed", "error" to it.javaClass.simpleName) }
    }
}
