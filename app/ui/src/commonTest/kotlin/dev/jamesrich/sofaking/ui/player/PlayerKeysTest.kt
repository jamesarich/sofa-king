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

    @Test
    fun `Up and Channel Up zap up while Down and Channel Down zap down`() {
        assertEquals(PlayerAction.ZAP_UP, Key.DirectionUp.playerAction())
        assertEquals(PlayerAction.ZAP_UP, Key.ChannelUp.playerAction())
        assertEquals(PlayerAction.ZAP_DOWN, Key.DirectionDown.playerAction())
        assertEquals(PlayerAction.ZAP_DOWN, Key.ChannelDown.playerAction())
    }

    @Test
    fun `Media Previous and Last Channel recall the last channel`() {
        assertEquals(PlayerAction.LAST_CHANNEL, Key.MediaPrevious.playerAction())
        assertEquals(PlayerAction.LAST_CHANNEL, Key.LastChannel.playerAction())
    }

    @Test
    fun `the media keys play and pause while Captions and C toggle captions and Info toggles the info bar`() {
        assertEquals(PlayerAction.PLAY_PAUSE, Key.MediaPlayPause.playerAction())
        assertEquals(PlayerAction.PLAY, Key.MediaPlay.playerAction())
        assertEquals(PlayerAction.PAUSE, Key.MediaPause.playerAction())
        assertEquals(PlayerAction.TOGGLE_CAPTIONS, Key.Captions.playerAction())
        assertEquals(PlayerAction.TOGGLE_CAPTIONS, Key.C.playerAction())
        assertEquals(PlayerAction.TOGGLE_INFO, Key.Info.playerAction())
    }

    @Test
    fun `Left and Right are the side keys and everything else has no player action`() {
        assertEquals(PlayerAction.LEFT, Key.DirectionLeft.playerAction())
        assertEquals(PlayerAction.RIGHT, Key.DirectionRight.playerAction())
        assertNull(Key.Back.playerAction())
        assertNull(Key.Guide.playerAction())
        assertNull(Key.Five.playerAction())
        assertNull(Key.DirectionCenter.playerAction())
    }

    @Test
    fun `in the channel list Up and Down go to the list while Left and Right are swallowed and the rest reach the player`() {
        assertEquals(MiniGuideKey.LIST, Key.DirectionUp.inMiniGuide())
        assertEquals(MiniGuideKey.LIST, Key.DirectionDown.inMiniGuide())
        assertEquals(MiniGuideKey.SWALLOWED, Key.DirectionLeft.inMiniGuide())
        assertEquals(MiniGuideKey.SWALLOWED, Key.DirectionRight.inMiniGuide())
        assertEquals(MiniGuideKey.PLAYER, Key.ChannelUp.inMiniGuide())
        assertEquals(MiniGuideKey.PLAYER, Key.Seven.inMiniGuide())
        assertEquals(MiniGuideKey.PLAYER, Key.Guide.inMiniGuide())
    }

    @Test
    fun `a tap closes the channel list before it toggles the info bar`() {
        assertEquals(PlayerTap.CLOSE_CHANNEL_LIST, playerTap(channelListOpen = true, infoVisible = true))
        assertEquals(PlayerTap.CLOSE_CHANNEL_LIST, playerTap(channelListOpen = true, infoVisible = false))
        assertEquals(PlayerTap.HIDE_INFO, playerTap(channelListOpen = false, infoVisible = true))
        assertEquals(PlayerTap.SHOW_INFO, playerTap(channelListOpen = false, infoVisible = false))
    }
}
