package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.ui.guide.gridStep
import dev.jamesrich.sofaking.ui.isOk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun `Up and Down zap while Channel Up and Channel Down step the channel number`() {
        assertEquals(PlayerAction.ZAP_UP, Key.DirectionUp.playerAction())
        assertEquals(PlayerAction.ZAP_DOWN, Key.DirectionDown.playerAction())
        assertEquals(PlayerAction.CHANNEL_UP, Key.ChannelUp.playerAction())
        assertEquals(PlayerAction.CHANNEL_DOWN, Key.ChannelDown.playerAction())
    }

    @Test
    fun `the D-pad zaps the way it moves the guide grid`() {
        assertEquals(Key.DirectionUp.gridStep()!!.rows, PlayerAction.ZAP_UP.zapStep())
        assertEquals(Key.DirectionDown.gridStep()!!.rows, PlayerAction.ZAP_DOWN.zapStep())
        assertNull(PlayerAction.TOGGLE_INFO.zapStep())
    }

    @Test
    fun `Channel Up goes to the next higher channel number and Channel Down to the next lower`() {
        assertEquals(1, PlayerAction.CHANNEL_UP.zapStep())
        assertEquals(-1, PlayerAction.CHANNEL_DOWN.zapStep())
    }

    @Test
    fun `Media Next and Previous step the channel as the session does while Last Channel recalls the last channel`() {
        assertEquals(PlayerAction.CHANNEL_DOWN, Key.MediaPrevious.playerAction())
        assertEquals(PlayerAction.CHANNEL_UP, Key.MediaNext.playerAction())
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
        assertEquals(MiniGuideKey.LIST, Key.DirectionLeft.inMiniGuide(chipsFocused = true))
        assertEquals(MiniGuideKey.LIST, Key.DirectionRight.inMiniGuide(chipsFocused = true))
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

    @Test
    fun `Left and Right adjust caption timing after CC or move into the info bar's buttons or show it`() {
        assertEquals(PlayerSideKey.ADJUST_CAPTION_TIMING, playerSideKey(timingAdjustable = true, infoVisible = true))
        assertEquals(PlayerSideKey.ADJUST_CAPTION_TIMING, playerSideKey(timingAdjustable = true, infoVisible = false))
        assertEquals(PlayerSideKey.FOCUS_BUTTONS, playerSideKey(timingAdjustable = false, infoVisible = true))
        assertEquals(PlayerSideKey.SHOW_INFO, playerSideKey(timingAdjustable = false, infoVisible = false))
    }

    @Test
    fun `in the stream card Up and Down move between tracks and the other keys close it`() {
        assertEquals(StreamCardKey.CARD, Key.DirectionUp.inStreamCard())
        assertEquals(StreamCardKey.CARD, Key.DirectionDown.inStreamCard())
        assertEquals(StreamCardKey.CLOSE, Key.DirectionLeft.inStreamCard())
        assertEquals(StreamCardKey.CLOSE, Key.Info.inStreamCard())
        assertEquals(StreamCardKey.CLOSE, Key.Back.inStreamCard())
        assertEquals(StreamCardKey.PLAYER, Key.ChannelUp.inStreamCard())
        assertEquals(StreamCardKey.PLAYER, Key.Captions.inStreamCard())
    }

    @Test
    fun `OK on the stream card goes to the focused track and closes a card with none`() {
        assertEquals(StreamCardKey.CARD, streamCardOk(hasTracks = true))
        assertEquals(StreamCardKey.CLOSE, streamCardOk(hasTracks = false))
    }

    @Test
    fun `the keypad Enter is OK too`() {
        assertTrue(Key.DirectionCenter.isOk)
        assertTrue(Key.Enter.isOk)
        assertTrue(Key.NumPadEnter.isOk)
        assertFalse(Key.Spacebar.isOk)
    }
}
