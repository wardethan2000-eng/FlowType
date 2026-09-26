package com.ethanward.flowtype.cleanup

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Which cleanup style a field gets, from the app and the field itself
 * (PLAN §4.5 part 3). Some fields get no cleanup at all.
 */
object AppStyle {
    const val MESSAGING = "messaging"
    const val EMAIL = "email"
    const val NOTES = "notes"
    const val SEARCH = "search"
    const val GENERAL = "general"

    private val MESSAGING_APPS = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.discord", "com.Slack",
        "com.facebook.orca", "com.instagram.android", "com.snapchat.android", "com.google.android.apps.dynamite",
        "com.microsoft.teams", "com.twitter.android", "com.reddit.frontpage",
    )
    private val EMAIL_APPS = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "ch.protonmail.android", "com.fastmail.app",
    )
    private val NOTES_APPS = setOf(
        "com.google.android.keep", "com.samsung.android.app.notes", "com.microsoft.office.onenote",
        "md.obsidian", "notion.id", "com.google.android.apps.docs.editors.docs",
    )

    /** The style for a field, or null when the field should get no cleanup. */
    fun forField(packageName: String?, inputType: Int, imeOptions: Int): String? {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (klass != InputType.TYPE_CLASS_TEXT && klass != InputType.TYPE_NULL) return null
        if (klass == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return null
            }
        }
        if (imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH) return SEARCH
        return when (packageName) {
            in MESSAGING_APPS -> MESSAGING
            in EMAIL_APPS -> EMAIL
            in NOTES_APPS -> NOTES
            else -> if (klass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT) EMAIL else GENERAL
        }
    }
}
