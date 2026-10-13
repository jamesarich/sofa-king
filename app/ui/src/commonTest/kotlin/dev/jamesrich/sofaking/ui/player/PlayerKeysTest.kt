package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class PlayerKeysTest {
    @Test
    fun `Guide and Menu leave the player for the guide at Now`() {
        assertEquals(PlayerExit.GUIDE_AT_NOW, Key.Guide.playerExit())
        assertEquals(PlayerExit.GUIDE_AT_NOW, Key.Menu.playerExit())
    }

    @Test
    fun `other keys stay in the player`() {
        assertNull(Key.Back.playerExit())
        assertNull(Key.DirectionCenter.playerExit())
        assertNull(Key.Info.playerExit())
        assertNull(Key.One.playerExit())
    }

    @Test
    fun `digits map from both key rows`() {
        assertEquals(7, Key.Seven.digitValue())
        assertEquals(7, Key.NumPad7.digitValue())
        assertNull(Key.Guide.digitValue())
    }

    @Test
    fun `Search leaves the player for guide search`() {
        assertEquals(PlayerExit.SEARCH, Key.Search.playerExit())
    }

    @Test
    fun `OK shows the info bar and OK on the info bar opens the channel list`() {
        assertEquals(PlayerOk.SHOW_INFO, playerOk(hasDigits = false, failed = false, infoVisible = false))
        assertEquals(PlayerOk.OPEN_CHANNEL_LIST, playerOk(hasDigits = false, failed = false, infoVisible = true))
    }

    @Test
    fun `typed digits commit before a failed stream retries`() {
        assertEquals(PlayerOk.COMMIT_NUMBER, playerOk(hasDigits = true, failed = true, infoVisible = true))
        assertEquals(PlayerOk.RETRY, playerOk(hasDigits = false, failed = true, infoVisible = true))
    }
}
