package dev.hermeskotlin.core.push

/** Decisions about pushes that don't need Android, kept here so they can be tested. */
object PushRules {

    /**
     * The session id the gateway's plugin gives the synthetic message of `profiles test` (the Settings check of which
     * profiles get through). It names no chat; it exists to be seen. Matches `herald_push/commands.py`.
     */
    const val PROFILE_TEST_SESSION = "herald-profile-test"

    /**
     * Whether a pushed bot message is shown as a notification. One for a real chat is not when the app is open and
     * connected (it shows it already) or the bot is hidden. The synthetic test message is always shown: it has no chat
     * to appear in, so holding it back would make the check look as if nothing got through.
     */
    fun showsBotMessage(session: String?, inSight: Boolean, connected: Boolean, botHidden: Boolean): Boolean {
        if (session == PROFILE_TEST_SESSION) return true
        if (inSight && connected) return false
        return !botHidden
    }
}
