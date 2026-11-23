package com.ethanward.flowtype.desktop

import com.ethanward.flowtype.Trace
import com.ethanward.flowtype.asr.SpokenCommands
import com.ethanward.flowtype.asr.dropGluedOkay
import com.ethanward.flowtype.cleanup.AlreadyClean
import com.ethanward.flowtype.cleanup.Cleaner
import com.ethanward.flowtype.cleanup.CleanupConfig
import com.ethanward.flowtype.cleanup.Protocol
import com.ethanward.flowtype.cleanup.Providers
import com.ethanward.flowtype.cleanup.Styles
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
    val noAi = "--no-ai" in args || !settings.cleanup
    val out = runCatching {
        Desktop(settings, Desktop.loadDictionary(File(dir, "dictionary.json"))) { id -> if (noAi) null else Desktop.key(id) }.run(raw)
    }.getOrElse {
        Trace.warn("desktop_failed", "error" to it.javaClass.simpleName)
        raw
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
    val style: String = Styles.GENERAL,
    val deadlineMs: Long = 3000,
    val soundsLike: Boolean = true,
    /** False: the local pass only, no AI call. */
    val cleanup: Boolean = true,
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
            return Settings(
                provider = j.optString("provider", d.provider),
                model = j.optString("model").takeIf { it.isNotEmpty() },
                server = j.optString("server").takeIf { it.isNotEmpty() },
                style = j.optString("style", d.style),
                deadlineMs = j.optLong("deadlineMs", d.deadlineMs),
                soundsLike = j.optBoolean("soundsLike", d.soundsLike),
                cleanup = j.optBoolean("cleanup", d.cleanup),
            )
        }
    }
}

/** The phone's order (DictationService): okay fix → spoken commands → dictionary → cleanup → dictionary again → snippets. */
class Desktop(
    private val settings: Settings,
    private val dictionary: Dictionary,
    /** The key for a provider id, or null for none. */
    private val key: (String) -> String?,
) {
    fun run(raw: String): String {
        val started = System.nanoTime()
        val pass = DictionaryPass(dictionary, settings.soundsLike)
        val local = pass.apply(SpokenCommands.apply(dropGluedOkay(raw))).text
        if (local.isBlank()) return pass.expand(local)

        val preset = Providers.byId(settings.provider)
        val baseUrl = Providers.baseUrl(preset, settings.server)
        val config = if (preset.protocol == Protocol.OPENAI_RESPONSES) CleanupConfig.byId(settings.model)
        else CleanupConfig.of(preset, settings.model ?: preset.suggestedModel.orEmpty())
        if (baseUrl == null || config.model.isEmpty()) {
            Trace.event("cleanup", "outcome" to "not_set_up", "provider" to preset.id)
            return pass.expand(local)
        }
        val k = key(preset.id) ?: if (preset.keyOptional) "" else null
        val style = settings.style
        val words = dictionary.words + dictionary.replacements.map { it.to }.filter { t -> t.any(Char::isUpperCase) }
        val result = Cleaner({ k }, null, Providers.provider(preset, baseUrl))
            .clean(local, style, words, config, settings.deadlineMs, null)
        val text = when (result) {
            is Cleaner.Result.Cleaned -> {
                Trace.event(
                    "cleanup", "outcome" to "cleaned", "model" to config.id, "style" to style,
                    "firstTokenMs" to result.firstTokenMs, "totalMs" to result.totalMs,
                    "charsIn" to local.length, "charsOut" to result.text.length,
                )
                // Pass 2: the model may have re-cased a dictionary word.
                pass.apply(result.text).text
            }
            is Cleaner.Result.Fallback -> {
                Trace.event(
                    "cleanup", "outcome" to result.reason, "model" to config.id, "style" to style,
                    "totalMs" to result.totalMs, "http" to result.http, "charsIn" to local.length,
                )
                when (result.reason) {
                    Cleaner.Reason.SHORT, Cleaner.Reason.CLEAN -> AlreadyClean.finish(local, style)
                    else -> local
                }
            }
        }
        Trace.event("desktop", "ms" to (System.nanoTime() - started) / 1_000_000)
        return pass.expand(text)
    }

    companion object {
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
