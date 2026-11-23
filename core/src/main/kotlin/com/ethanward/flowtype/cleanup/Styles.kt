package com.ethanward.flowtype.cleanup

/**
 * The cleanup styles (PLAN §4.5 part 3). Which one a field gets is the
 * platform's call: on the phone, AppStyle picks from the app and the field.
 */
object Styles {
    const val MESSAGING = "messaging"
    const val EMAIL = "email"
    const val NOTES = "notes"
    const val SEARCH = "search"
    const val GENERAL = "general"
}
