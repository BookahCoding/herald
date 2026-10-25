package dev.hermeskotlin.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushRulesTest {

    private val chat = "stored-session-1"

    @Test
    fun aRealMessageIsShownWhenTheAppIsClosedOrNotConnected() {
        assertTrue(PushRules.showsBotMessage(chat, inSight = false, connected = true, botHidden = false))
        assertTrue(PushRules.showsBotMessage(chat, inSight = false, connected = false, botHidden = false))
        // Open but not connected: the app can't show it, so the notification must.
        assertTrue(PushRules.showsBotMessage(chat, inSight = true, connected = false, botHidden = false))
    }

    @Test
    fun aRealMessageIsNotShownWhileTheAppIsOpenAndConnected() {
        assertFalse(PushRules.showsBotMessage(chat, inSight = true, connected = true, botHidden = false))
    }

    @Test
    fun aHiddenBotStaysQuiet() {
        assertFalse(PushRules.showsBotMessage(chat, inSight = false, connected = true, botHidden = true))
    }

    @Test
    fun theTestMessageIsShownWhateverElseIsTrue() {
        for (inSight in listOf(true, false)) for (connected in listOf(true, false)) for (hidden in listOf(true, false)) {
            assertTrue(PushRules.showsBotMessage(PushRules.PROFILE_TEST_SESSION, inSight, connected, hidden), "$inSight $connected $hidden")
        }
    }

    @Test
    fun theTestSessionIsTheOneThePluginSends() {
        // herald_push/commands.py: session="herald-profile-test". A change on either side has to change both.
        assertEquals("herald-profile-test", PushRules.PROFILE_TEST_SESSION)
        assertTrue(Regex("^[A-Za-z0-9_.:-]{1,128}$").matches(PushRules.PROFILE_TEST_SESSION), "it must pass the listener's session check")
    }
}
