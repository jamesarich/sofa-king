package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ZapScope
import dev.jamesrich.sofaking.data.zapRing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerStateTest {
    private fun channel(id: String, number: Double = id.toDouble()) =
        Channel(id, number, "Ch $id", null, listOf("Test"))
    private val lineup = listOf(channel("1"), channel("2"), channel("3"), channel("4"))

    /** Records what the keys asked of the stream and the way out. */
    private class Controls : PlayerControls {
        val calls = mutableListOf<String>()
        override fun guide() {
            calls += "guide"
        }
        override fun search() {
            calls += "search"
        }
        override fun toggleCaptions() {
            calls += "captions"
        }
        override fun adjustCaptionTiming(direction: Int) {
            calls += "timing $direction"
        }
        override fun playPause() {
            calls += "playPause"
        }
        override fun play() {
            calls += "play"
        }
        override fun pause() {
            calls += "pause"
        }
        override fun retry() {
            calls += "retry"
        }
        override fun focusButtons() {
            calls += "buttons"
        }
        override fun focusPicture() {
            calls += "picture"
        }
        override fun record() {
            calls += "record"
        }
        override fun zoom() {
            calls += "zoom"
        }
        override fun stop() {
            calls += "stop"
        }
    }

    private val controls = Controls()

    private fun PlayerState.down(key: Key, hasAudioTracks: Boolean = false) =
        onKeyEvent(key, KeyEventType.KeyDown, controls, hasAudioTracks = hasAudioTracks)

    private fun PlayerState.up(key: Key, hasAudioTracks: Boolean = false) =
        onKeyEvent(key, KeyEventType.KeyUp, controls, hasAudioTracks = hasAudioTracks)

    /** A short press: key-down then key-up. */
    private fun PlayerState.press(key: Key) {
        down(key)
        up(key)
    }

    @Test
    fun `the player opens on its channel with the info bar up`() {
        val state = PlayerState(lineup, startIndex = 2)
        assertEquals("3", state.currentChannel?.guideId)
        assertTrue(state.overlayVisible)
        assertFalse(state.miniGuideOpen)
    }

    @Test
    fun `OK on the info bar opens the channel list and OK without it shows the bar`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.press(Key.DirectionCenter)
        assertTrue(state.miniGuideOpen)
        assertFalse(state.overlayVisible)

        val hidden = PlayerState(lineup, startIndex = 0)
        hidden.overlayVisible = false
        hidden.press(Key.DirectionCenter)
        assertTrue(hidden.overlayVisible)
        assertFalse(hidden.miniGuideOpen)
    }

    @Test
    fun `OK on a failed stream retries before anything else`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.fail("SESSION_UNAVAILABLE")
        state.press(Key.Enter)
        assertEquals(listOf("retry"), controls.calls)
        assertFalse(state.miniGuideOpen)
    }

    @Test
    fun `Up and Down zap through the ring and wrap at its ends`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.DirectionUp)
        assertEquals("4", state.currentChannel?.guideId)
        state.down(Key.DirectionDown)
        assertEquals("1", state.currentChannel?.guideId)
        state.down(Key.ChannelUp)
        assertEquals("2", state.currentChannel?.guideId)
    }

    @Test
    fun `Last and a long OK go back to the channel before`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.DirectionDown)
        state.down(Key.LastChannel)
        assertEquals("1", state.currentChannel?.guideId)
        assertEquals("2", state.previousChannel?.guideId)

        state.onKeyEvent(Key.DirectionCenter, KeyEventType.KeyDown, controls)
        state.onKeyEvent(Key.DirectionCenter, KeyEventType.KeyDown, controls, repeatCount = 1)
        state.up(Key.DirectionCenter)
        assertEquals("2", state.currentChannel?.guideId)
        assertFalse(state.miniGuideOpen)
    }

    @Test
    fun `Last with nothing watched before shows the info bar`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.overlayVisible = false
        state.down(Key.LastChannel)
        assertEquals("1", state.currentChannel?.guideId)
        assertTrue(state.overlayVisible)
    }

    @Test
    fun `the channel watched before the player opened is the first Last`() {
        val state = PlayerState(lineup, startIndex = 0, previousGuideId = "3")
        state.down(Key.LastChannel)
        assertEquals("3", state.currentChannel?.guideId)
    }

    @Test
    fun `digits and OK tune the channel with that number`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.Three)
        assertTrue(state.numbers.hasDigits)
        state.press(Key.DirectionCenter)
        assertEquals("3", state.currentChannel?.guideId)
        assertFalse(state.numbers.hasDigits)
        assertFalse(state.miniGuideOpen)
    }

    @Test
    fun `a number no channel has shows a notice`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.Nine)
        state.press(Key.DirectionCenter)
        assertEquals("9", state.numbers.miss)
        assertEquals("1", state.currentChannel?.guideId)
    }

    @Test
    fun `a number outside the filter widens the ring and tunes once it has`() {
        val scope = ZapScope("Favorites", setOf("1", "2"))
        val state = PlayerState(lineup, startIndex = 0, zapScope = scope)
        assertEquals(listOf("1", "2"), state.channels.map { it.guideId })
        state.down(Key.Four)
        state.press(Key.DirectionCenter)
        assertTrue(state.widened)
        assertEquals("4", state.pendingNumber)

        val ring = zapRing(lineup, scope, state.widened)
        state.channels = ring
        state.followRing(ring)
        assertEquals("4", state.currentChannel?.guideId)
        assertNull(state.pendingNumber)
    }

    @Test
    fun `a refreshed lineup keeps the tuned channel on its row`() {
        val state = PlayerState(lineup, startIndex = 1)
        state.tuned = state.currentChannel
        val refreshed = listOf(channel("0"), channel("1"), channel("3"), channel("2"))
        state.channels = refreshed
        state.followRing(refreshed)
        assertEquals("2", state.currentChannel?.guideId)
        assertEquals(3, state.index)
    }

    @Test
    fun `the Guide and Search keys leave for the guide`() {
        val state = PlayerState(lineup, startIndex = 0)
        assertTrue(state.down(Key.Guide))
        assertTrue(state.down(Key.Search))
        assertEquals(listOf("guide", "search"), controls.calls)
    }

    @Test
    fun `media keys and CC reach the stream`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.MediaPlayPause)
        state.down(Key.MediaPlay)
        state.down(Key.MediaPause)
        state.down(Key.Captions)
        assertEquals(listOf("playPause", "play", "pause", "captions"), controls.calls)
    }

    @Test
    fun `play and pause bring the bar up`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.MediaPlayPause)
        assertTrue(state.overlayVisible)
        state.overlayVisible = false
        state.down(Key.MediaPlay)
        assertTrue(state.overlayVisible)
        state.overlayVisible = false
        state.down(Key.MediaPause)
        assertTrue(state.overlayVisible)
    }

    @Test
    fun `Record Zoom and Stop reach the player and Audio Track opens the stream card`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.MediaRecord)
        state.down(Key.TvZoomMode)
        state.down(Key.MediaStop)
        assertEquals(listOf("record", "zoom", "stop"), controls.calls)
        state.down(Key.MediaAudioTrack)
        assertTrue(state.streamCardOpen)
    }

    @Test
    fun `a short Info toggles the info bar and a long one opens the stream card`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.press(Key.Info)
        assertFalse(state.overlayVisible)
        state.press(Key.Info)
        assertTrue(state.overlayVisible)

        state.onKeyEvent(Key.Info, KeyEventType.KeyDown, controls, isLongPress = true)
        assertTrue(state.streamCardOpen)
        assertFalse(state.overlayVisible)
        state.up(Key.Info)
        assertTrue(state.streamCardOpen)
        state.press(Key.Info)
        assertFalse(state.streamCardOpen)
    }

    @Test
    fun `OK on the stream card goes to its track rows or closes it when there are none`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.openStreamCard()
        assertFalse(state.down(Key.DirectionCenter, hasAudioTracks = true))
        assertTrue(state.streamCardOpen)

        assertTrue(state.down(Key.DirectionCenter))
        assertTrue(state.streamCardOpen)
        assertTrue(state.up(Key.DirectionCenter))
        assertFalse(state.streamCardOpen)
    }

    @Test
    fun `a player key on the stream card closes it and acts`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.openStreamCard()
        assertFalse(state.down(Key.DirectionDown))
        assertTrue(state.streamCardOpen)
        assertTrue(state.down(Key.ChannelUp))
        assertFalse(state.streamCardOpen)
        assertEquals("2", state.currentChannel?.guideId)
    }

    @Test
    fun `Back on an open panel closes it and its key-up goes nowhere`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.openMiniGuide()
        assertTrue(state.down(Key.Back))
        assertFalse(state.miniGuideOpen)
        assertTrue(state.up(Key.Back))
        assertTrue(state.back(controls))
    }

    @Test
    fun `a Back key-up that misses the surface ends the press that closed the panel`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.openStreamCard()
        state.down(Key.Back)
        assertFalse(state.back(controls))
        assertTrue(state.back(controls))
    }

    @Test
    fun `the system Back closes the stream card then the channel list then leaves`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.openMiniGuide()
        assertFalse(state.back(controls))
        assertFalse(state.miniGuideOpen)
        state.openStreamCard()
        assertFalse(state.back(controls))
        assertFalse(state.streamCardOpen)
        assertTrue(state.back(controls))
    }

    @Test
    fun `the system Back on the bar's buttons goes back to the picture and keeps the bar`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.showInfo()
        state.buttonsFocused = true
        assertFalse(state.back(controls))
        assertEquals(listOf("picture"), controls.calls)
        assertTrue(state.overlayVisible)
    }

    @Test
    fun `in the channel list Up and Down move the list and Left and Right only move along the chips`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.openMiniGuide()
        assertFalse(state.down(Key.DirectionDown))
        assertTrue(state.down(Key.DirectionRight))
        state.miniGuideChipsFocused = true
        assertFalse(state.down(Key.DirectionRight))
        assertTrue(state.miniGuideOpen)
        assertFalse(state.down(Key.DirectionCenter))
        assertTrue(state.miniGuideOpen)
    }

    @Test
    fun `a channel picked in the list outside the ring widens it first`() {
        val state = PlayerState(lineup, startIndex = 0, zapScope = ZapScope("News", setOf("1", "2")))
        state.openMiniGuide()
        state.tuneChannel(lineup[1])
        assertEquals("2", state.currentChannel?.guideId)
        state.openMiniGuide()
        state.tuneChannel(lineup[3])
        assertFalse(state.miniGuideOpen)
        assertTrue(state.widened)
        assertEquals("4", state.pendingNumber)
    }

    @Test
    fun `Left and Right move into the info bar's buttons and show the bar otherwise`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.down(Key.DirectionLeft)
        assertEquals(listOf("buttons"), controls.calls)
        assertFalse(state.miniGuideOpen)
        assertTrue(state.overlayVisible)

        val hidden = PlayerState(lineup, startIndex = 0)
        hidden.overlayVisible = false
        hidden.down(Key.DirectionRight)
        assertTrue(hidden.overlayVisible)
        assertFalse(hidden.miniGuideOpen)
    }

    @Test
    fun `after CC Left and Right move the caption timing`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.captionsToggled(enabled = true, channelHasText = true, leadSupported = true, timingMs = 1_500)
        assertTrue(state.captionsOn)
        assertEquals(1_500, state.captionToast?.timingMs)
        state.down(Key.DirectionRight)
        state.down(Key.DirectionLeft)
        assertEquals(listOf("timing 1", "timing -1"), controls.calls)
        assertFalse(state.miniGuideOpen)
    }

    @Test
    fun `the caption timing is not offered where the engine cannot apply it`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.captionsToggled(enabled = true, channelHasText = true, leadSupported = false, timingMs = 1_500)
        assertNotNull(state.captionToast)
        assertNull(state.captionToast?.timingMs)
    }

    @Test
    fun `a timing step is kept for the channel and reported`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.captionsToggled(enabled = true, channelHasText = true, leadSupported = true, timingMs = 1_500)
        val saved = mutableListOf<Pair<String, Int>>()
        state.adjustCaptionTiming(1, advance = 1_500) { channel, ms -> saved += channel.guideId to ms }
        val next = CaptionPolicy.stepAdvance(1_500, 1)
        assertEquals(listOf("1" to next), saved)
        assertEquals(next, state.captionToast?.timingMs)
        assertEquals(next, state.captionAdvance { 0 })
    }

    @Test
    fun `an ended stream is a failure and playing clears it`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.onStatus(PlayerStatus(state = PlaybackState.ENDED))
        assertEquals("STREAM_ENDED", state.failure?.code)
        state.fail("RELAY", immediate = true)
        assertEquals(Failure("RELAY", attempt = 0, immediate = true), state.failure)
        state.onStatus(PlayerStatus(state = PlaybackState.READY))
        assertNull(state.failure)
    }

    @Test
    fun `a zap clears the failure`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.fail("SESSION_UNAVAILABLE")
        state.down(Key.DirectionDown)
        assertNull(state.failure)
    }

    @Test
    fun `a tap closes the stream card or the channel list and otherwise toggles the info bar`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.tap()
        assertFalse(state.overlayVisible)
        state.tap()
        assertTrue(state.overlayVisible)
        state.openMiniGuide()
        state.tap()
        assertFalse(state.miniGuideOpen)
        state.openStreamCard()
        state.tap()
        assertFalse(state.streamCardOpen)
    }

    @Test
    fun `the key legend rides the first few info bars`() {
        val state = PlayerState(lineup, startIndex = 0, keyLegendShown = AppSettings.KEY_LEGEND_BARS - 1)
        assertEquals(AppSettings.KEY_LEGEND_BARS, state.legendForNewBar())
        assertTrue(state.legendOnThisBar)
        assertNull(state.legendForNewBar())
        assertFalse(state.legendOnThisBar)
    }

    @Test
    fun `with no channels the keys go to the focused node`() {
        val state = PlayerState(emptyList(), startIndex = 0)
        assertNull(state.currentChannel)
        assertFalse(state.down(Key.DirectionUp))
        assertFalse(state.down(Key.DirectionCenter))
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun `on the bar's buttons Left Right and OK go to them while Up and Back go back to the picture`() {
        val state = PlayerState(lineup, startIndex = 1)
        state.buttonsFocused = true
        assertFalse(state.down(Key.DirectionRight))
        assertFalse(state.down(Key.DirectionCenter))
        assertTrue(state.down(Key.DirectionUp))
        assertTrue(state.up(Key.DirectionUp))
        assertTrue(state.down(Key.Back))
        assertTrue(state.up(Key.Back))
        assertEquals(listOf("picture", "picture"), controls.calls)
        assertEquals("2", state.currentChannel?.guideId, "no zap while a button has focus")
    }

    @Test
    fun `a media key on the bar's buttons goes back to the picture and acts there`() {
        val state = PlayerState(lineup, startIndex = 0)
        state.buttonsFocused = true
        assertTrue(state.down(Key.MediaPlayPause))
        assertEquals(listOf("picture", "playPause"), controls.calls)
    }

    @Test
    fun `the bar stays up while paused or failed and leaves otherwise`() {
        assertFalse(barHeld(paused = false, scrubbing = false, failed = false, autoSkip = false))
        assertTrue(barHeld(paused = true, scrubbing = false, failed = false, autoSkip = false))
        assertTrue(barHeld(paused = false, scrubbing = false, failed = true, autoSkip = false))
    }

    @Test
    fun `a screen reader holds the bar`() {
        assertTrue(barHeld(paused = false, scrubbing = false, failed = false, autoSkip = false, screenReader = true))
    }

    @Test
    fun `a scrub or an intro countdown holds the bar`() {
        assertTrue(barHeld(paused = false, scrubbing = true, failed = false, autoSkip = false))
        assertTrue(barHeld(paused = false, scrubbing = false, failed = false, autoSkip = true))
    }

    @Test
    fun `side cards rest for fifteen seconds and notices outlast state toasts`() {
        assertEquals(15_000L, PANEL_IDLE_MS)
        assertTrue(NOTICE_TOAST_MS > STATE_TOAST_MS)
    }
}
