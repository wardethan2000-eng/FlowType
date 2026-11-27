package com.ethanward.flowtype.desktop

import com.ethanward.flowtype.cleanup.Styles
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The focused desktop window, as KWin reports it (never logged: [caption] can carry private titles). */
data class ActiveWindow(
    val resourceClass: String,
    val resourceName: String,
    val caption: String,
)

/**
 * Picks a FlowType cleanup [Styles] name from the active Linux window, matching
 * the phone's AppStyle behavior (PLAN §7 Phase 5): messaging apps and chat web
 * tabs get [Styles.MESSAGING], mail clients and webmail get [Styles.EMAIL],
 * note/doc apps get [Styles.NOTES], and everything else gets [Styles.GENERAL].
 */
object WindowStyle {
    const val AUTO = "auto"

    private val VALID_STYLES = setOf(Styles.MESSAGING, Styles.EMAIL, Styles.NOTES, Styles.SEARCH, Styles.GENERAL)

    private val MESSAGING_APPS = setOf(
        "discord", "vesktop", "armcord", "webcord", "legcord",
        "slack",
        "telegram", "telegramdesktop", "org.telegram.desktop", "ayugram",
        "signal", "signal-desktop",
        "element", "element-desktop",
        "whatsapp", "wasistlos", "zapzap",
        "beeper", "beepertexts", "ferdium", "franz", "rambox",
        "teams", "teams-for-linux", "mattermost", "zulip",
        "cinny", "neochat", "org.kde.neochat", "konversation", "org.kde.konversation",
        "pidgin", "hexchat", "caprine", "android-messages-desktop",
    )

    private val EMAIL_APPS = setOf(
        "thunderbird", "org.mozilla.thunderbird", "betterbird", "eu.betterbird.betterbird",
        "kmail", "org.kde.kmail2", "kontact", "org.kde.kontact",
        "evolution", "org.gnome.evolution", "geary", "org.gnome.geary",
        "mailspring", "proton-mail", "proton mail", "tutanota-desktop", "claws-mail",
    )

    private val NOTES_APPS = setOf(
        "obsidian", "logseq", "joplin", "standard notes", "notesnook", "anytype",
        "notion", "notion-app", "notion-app-enhanced",
        "libreoffice-writer", "typora", "marktext", "zettlr", "apostrophe", "ghostwriter",
        "org.kde.kjots", "org.kde.kwrite",
    )

    private val BROWSERS = setOf(
        "firefox", "firefox-esr", "firefoxdeveloperedition", "org.mozilla.firefox",
        "google-chrome", "google-chrome-beta", "google-chrome-unstable",
        "chromium", "chromium-browser",
        "brave-browser", "brave",
        "vivaldi", "vivaldi-stable",
        "zen", "zen-browser", "app.zen_browser.zen",
        "librewolf", "floorp", "opera", "microsoft-edge", "org.kde.falkon",
    )

    /** Browser tab title markers (case-insensitive) when the active window is a browser. */
    private val WEB_MESSAGING = listOf(
        "messages for web", "google messages", "discord", "slack", "whatsapp",
        "telegram", "messenger", "instagram", "snapchat", "reddit",
        "microsoft teams", "google chat", "twitter", " / x", "x / ", "signal", "element", "beeper",
    )

    private val WEB_EMAIL = listOf(
        "gmail", "outlook", "proton mail", "fastmail", "yahoo mail", "icloud mail",
        "roundcube", "tuta mail", "mail - ", " - mail",
    )

    private val WEB_NOTES = listOf(
        "google docs", "google keep", "notion", "obsidian", "onenote",
        "evernote", "coda", "confluence", "simplenote", "hackmd",
    )

    /**
     * Resolves the style for [window]. [picked] is the user's `appStyles` map
     * from `desktop.json`: keys match [ActiveWindow.resourceClass] or
     * [ActiveWindow.resourceName] (exact, case-insensitive) or, for browsers,
     * a case-insensitive substring of [ActiveWindow.caption].
     */
    fun forWindow(window: ActiveWindow?, picked: Map<String, String> = emptyMap()): String {
        if (window == null) return Styles.GENERAL
        val cls = window.resourceClass.trim().lowercase()
        val name = window.resourceName.trim().lowercase()
        val caption = window.caption.trim().lowercase()
        val isBrowser = cls in BROWSERS || name in BROWSERS

        if (picked.isNotEmpty()) {
            val normalized = picked.entries
                .mapNotNull { (k, v) -> v.lowercase().takeIf { it in VALID_STYLES }?.let { k.trim().lowercase() to it } }
            normalized.firstOrNull { (k, _) -> k.isNotEmpty() && (k == cls || k == name) }?.let { return it.second }
            if (isBrowser) {
                normalized.firstOrNull { (k, _) -> k.isNotEmpty() && k in caption }?.let { return it.second }
            }
        }

        if (cls in MESSAGING_APPS || name in MESSAGING_APPS) return Styles.MESSAGING
        if (cls in EMAIL_APPS || name in EMAIL_APPS) return Styles.EMAIL
        if (cls in NOTES_APPS || name in NOTES_APPS) return Styles.NOTES

        if (isBrowser && caption.isNotEmpty()) {
            if (WEB_MESSAGING.any { it in caption }) return Styles.MESSAGING
            if (WEB_EMAIL.any { it in caption }) return Styles.EMAIL
            if (WEB_NOTES.any { it in caption }) return Styles.NOTES
        }
        return Styles.GENERAL
    }

    /**
     * Queries KWin (KDE Plasma 6 Wayland/X11) for the active window via a
     * one-shot KWin script + `dbus-monitor`, taking ~30 ms with no daemon.
     * Returns null on non-KDE sessions or any error/timeout.
     */
    fun queryKWin(timeoutMs: Long = 400): ActiveWindow? = runCatching {
        val pid = ProcessHandle.current().pid()
        val nonce = System.nanoTime() and 0xffff
        val tag = "flowtype_${pid}_$nonce"
        val bus = "com.ethanward.flowtype.win_${pid}_$nonce"
        val script = File.createTempFile("flowtype-kwin-", ".js")
        var mon: Process? = null
        var loaded = false
        try {
            script.writeText(
                """
                var w = workspace.activeWindow;
                var p = w ? [w.resourceClass, w.resourceName, w.caption]
                    .map(function(s) { return encodeURIComponent(String(s || "")); }).join("\t") : "";
                callDBus("$bus", "/", "$bus", "Report", p);
                """.trimIndent(),
            )
            mon = ProcessBuilder("dbus-monitor", "--session", "type=method_call,interface=$bus,member=Report")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val ready = CountDownLatch(1)
            val got = CountDownLatch(1)
            val payload = AtomicReference<String?>()
            val proc = mon
            val reader = Thread({
                runCatching {
                    proc.inputStream.bufferedReader().use { br ->
                        var seenCall = false
                        while (true) {
                            val line = br.readLine() ?: break
                            ready.countDown()
                            if ("member=Report" in line) {
                                seenCall = true
                            } else if (seenCall) {
                                val start = line.indexOf("string \"")
                                if (start >= 0) {
                                    val end = line.lastIndexOf('"')
                                    if (end > start + 8) {
                                        payload.set(line.substring(start + 8, end))
                                    }
                                    got.countDown()
                                    break
                                }
                            }
                        }
                    }
                }
            }, "flowtype-kwin-read").apply { isDaemon = true; start() }

            // Wait briefly for dbus-monitor to connect to the session bus before firing the script.
            ready.await(80, TimeUnit.MILLISECONDS)

            val sid = runBusctl(timeoutMs, "org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "loadScript", "ss", script.absolutePath, tag)
                ?.trim()?.substringAfterLast(' ')?.toIntOrNull()
                ?: return@runCatching null
            if (sid < 0) return@runCatching null
            loaded = true
            runBusctl(timeoutMs, "org.kde.KWin", "/Scripting/Script$sid", "org.kde.kwin.Script", "run")
            got.await(timeoutMs, TimeUnit.MILLISECONDS)
            reader.interrupt()
            parsePayload(payload.get())
        } finally {
            if (loaded) {
                runCatching {
                    runBusctl(300, "org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", "unloadScript", "s", tag)
                }
            }
            mon?.destroyForcibly()
            script.delete()
        }
    }.getOrNull()

    internal fun parsePayload(encoded: String?): ActiveWindow? {
        if (encoded.isNullOrEmpty()) return null
        val parts = encoded.split('\t')
        if (parts.size < 3) return null
        return ActiveWindow(
            resourceClass = URLDecoder.decode(parts[0], Charsets.UTF_8),
            resourceName = URLDecoder.decode(parts[1], Charsets.UTF_8),
            caption = URLDecoder.decode(parts[2], Charsets.UTF_8),
        )
    }

    private fun runBusctl(timeoutMs: Long, vararg args: String): String? {
        val p = ProcessBuilder(listOf("busctl", "--user", "call") + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        if (p.exitValue() != 0) return null
        return p.inputStream.readBytes().toString(Charsets.UTF_8)
    }
}
