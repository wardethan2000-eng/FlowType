package com.ethanward.flowtype.desktop

import com.ethanward.flowtype.cleanup.Styles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowStyleTest {
    @Test
    fun nativeAppsPickMatchingStyle() {
        assertEquals(Styles.MESSAGING, WindowStyle.forWindow(ActiveWindow("discord", "discord", "#general - Discord")))
        assertEquals(Styles.MESSAGING, WindowStyle.forWindow(ActiveWindow("org.telegram.desktop", "telegram-desktop", "Telegram")))
        assertEquals(Styles.MESSAGING, WindowStyle.forWindow(ActiveWindow("Slack", "slack", "Slack | Team")))
        assertEquals(Styles.EMAIL, WindowStyle.forWindow(ActiveWindow("org.mozilla.thunderbird", "thunderbird", "Inbox")))
        assertEquals(Styles.NOTES, WindowStyle.forWindow(ActiveWindow("obsidian", "obsidian", "Vault - Obsidian")))
        assertEquals(Styles.GENERAL, WindowStyle.forWindow(ActiveWindow("org.kde.konsole", "konsole", "~ : bash — Konsole")))
        assertEquals(Styles.GENERAL, WindowStyle.forWindow(ActiveWindow("code", "code", "Main.kt - VS Code")))
    }

    @Test
    fun browserTabsPickStyleFromCaption() {
        assertEquals(Styles.MESSAGING, WindowStyle.forWindow(ActiveWindow("firefox", "Navigator", "Messages for web — Mozilla Firefox")))
        assertEquals(Styles.MESSAGING, WindowStyle.forWindow(ActiveWindow("google-chrome", "google-chrome", "Discord | #dev - Google Chrome")))
        assertEquals(Styles.EMAIL, WindowStyle.forWindow(ActiveWindow("google-chrome", "google-chrome", "Inbox (2) - ethan@example.com - Gmail - Google Chrome")))
        assertEquals(Styles.EMAIL, WindowStyle.forWindow(ActiveWindow("firefox", "Navigator", "Proton Mail — Mozilla Firefox")))
        assertEquals(Styles.NOTES, WindowStyle.forWindow(ActiveWindow("brave-browser", "brave-browser", "Project Notes - Google Docs - Brave")))
        assertEquals(Styles.GENERAL, WindowStyle.forWindow(ActiveWindow("firefox", "Navigator", "Claude — Mozilla Firefox")))
        assertEquals(Styles.GENERAL, WindowStyle.forWindow(ActiveWindow("firefox", "Navigator", "GitHub - wardethan2000-eng/FlowType")))
    }

    @Test
    fun userOverridesWinOverBuiltIns() {
        val picked = mapOf("org.kde.konsole" to Styles.NOTES, "claude" to Styles.MESSAGING)
        assertEquals(Styles.NOTES, WindowStyle.forWindow(ActiveWindow("org.kde.konsole", "konsole", "Konsole"), picked))
        assertEquals(Styles.MESSAGING, WindowStyle.forWindow(ActiveWindow("firefox", "Navigator", "Claude — Mozilla Firefox"), picked))
        assertEquals(Styles.GENERAL, WindowStyle.forWindow(null, picked))
    }

    @Test
    fun encodedPayloadDecodesUnicodeAndTabs() {
        val w = WindowStyle.parsePayload("org.kde.konsole\tkonsole\t~%20%3A%20agy%20%E2%80%94%20Konsole")
        assertEquals(ActiveWindow("org.kde.konsole", "konsole", "~ : agy — Konsole"), w)
        assertNull(WindowStyle.parsePayload(""))
        assertNull(WindowStyle.parsePayload(null))
    }
}
