package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAccessTest {
    @Test
    fun `a controller the system trusts connects`() {
        assertTrue(admits(trusted = true, controllerPackage = "com.android.systemui", ownPackage = "dev.app"))
    }

    @Test
    fun `a controller from the app itself connects`() {
        assertTrue(admits(trusted = false, controllerPackage = "dev.app", ownPackage = "dev.app"))
    }

    @Test
    fun `an untrusted controller from another app is rejected`() {
        assertFalse(admits(trusted = false, controllerPackage = "evil.app", ownPackage = "dev.app"))
    }

    @Test
    fun `controllers keep playback and seek commands`() {
        listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        ).forEach { assertFalse("command $it", it in WITHHELD_COMMANDS) }
    }

    @Test
    fun `controllers lose the commands that change what plays`() {
        listOf(
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_RELEASE,
        ).forEach { assertTrue("command $it", it in WITHHELD_COMMANDS) }
    }
}
