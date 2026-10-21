package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerButtonsTest {
    @Test
    fun `a library episode offers its tracks and start over and the next episode`() {
        assertEquals(
            listOf(VodButton.TRACKS, VodButton.START_OVER, VodButton.NEXT),
            vodButtons(failed = false, inBreak = false, tracksPickable = true, captions = false, pastStart = true, hasNext = true),
        )
    }

    @Test
    fun `a recording in a break leads with Skip break and offers its captions`() {
        assertEquals(
            listOf(VodButton.SKIP_BREAK, VodButton.CAPTIONS),
            vodButtons(failed = false, inBreak = true, tracksPickable = false, captions = true, pastStart = false, hasNext = false),
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
