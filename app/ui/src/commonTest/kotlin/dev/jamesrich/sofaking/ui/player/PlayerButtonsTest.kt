package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerButtonsTest {
    @Test
    fun `a library episode offers its tracks and zoom and start over and the next episode`() {
        assertEquals(
            listOf(VodButton.TRACKS, VodButton.ZOOM, VodButton.START_OVER, VodButton.NEXT),
            vodButtons(failed = false, inBreak = false, tracksPickable = true, captions = false, pastStart = true, hasNext = true),
        )
    }

    @Test
    fun `a recording in a break leads with Skip break and offers its captions`() {
        assertEquals(
            listOf(VodButton.SKIP_BREAK, VodButton.CAPTIONS, VodButton.ZOOM),
            vodButtons(failed = false, inBreak = true, tracksPickable = false, captions = true, pastStart = false, hasNext = false),
        )
    }

    @Test
    fun `an episode in its intro leads with Skip intro`() {
        assertEquals(
            listOf(VodButton.SKIP_INTRO, VodButton.TRACKS, VodButton.ZOOM, VodButton.NEXT),
            vodButtons(failed = false, inBreak = false, tracksPickable = true, captions = false, pastStart = false, hasNext = true, inIntro = true),
        )
    }

    @Test
    fun `a failed stream offers no buttons`() {
        assertEquals(
            emptyList(),
            vodButtons(failed = true, inBreak = true, tracksPickable = true, captions = true, pastStart = true, hasNext = true),
        )
    }

    @Test
    fun `the live bar keeps zoom beside the stream card and Previous and Record only where they apply`() {
        assertEquals(
            listOf(LiveButton.CHANNELS, LiveButton.PLAY_PAUSE, LiveButton.CAPTIONS, LiveButton.STREAM_INFO, LiveButton.ZOOM, LiveButton.GUIDE),
            liveButtons(pointer = false, canRecord = false, canFavorite = false),
        )
        assertEquals(
            listOf(
                LiveButton.CHANNELS,
                LiveButton.PREVIOUS,
                LiveButton.CAPTIONS,
                LiveButton.STREAM_INFO,
                LiveButton.ZOOM,
                LiveButton.RECORD,
                LiveButton.FAVORITE,
                LiveButton.GUIDE,
            ),
            liveButtons(pointer = true, canRecord = true, canFavorite = true, hasPrevious = true),
        )
    }

    @Test
    fun `zoom steps from fit to fill to stretch and back`() {
        assertEquals(VideoZoom.FILL, VideoZoom.FIT.next())
        assertEquals(VideoZoom.STRETCH, VideoZoom.FILL.next())
        assertEquals(VideoZoom.FIT, VideoZoom.STRETCH.next())
    }

    @Test
    fun `on the row Left Right and OK stay while Up and Back leave and media keys go back to the picture`() {
        assertEquals(ButtonRowKey.ROW, Key.DirectionLeft.inButtonRow())
        assertEquals(ButtonRowKey.ROW, Key.DirectionCenter.inButtonRow())
        assertEquals(ButtonRowKey.ROW, Key.DirectionDown.inButtonRow())
        assertEquals(ButtonRowKey.LEAVE, Key.DirectionUp.inButtonRow())
        assertEquals(ButtonRowKey.LEAVE, Key.Back.inButtonRow())
        assertEquals(ButtonRowKey.LEAVE, Key.Escape.inButtonRow())
        assertEquals(ButtonRowKey.PLAYER, Key.MediaPlayPause.inButtonRow())
    }

    @Test
    fun `a double tap seeks by the third of the screen it lands in`() {
        assertEquals(-1, doubleTapSeek(100f, 900f))
        assertEquals(0, doubleTapSeek(450f, 900f))
        assertEquals(1, doubleTapSeek(800f, 900f))
        assertEquals(0, doubleTapSeek(10f, 0f))
    }
}
