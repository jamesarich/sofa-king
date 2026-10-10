package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionGeometryTest {
    private val width = 1920
    private val height = 1000
    private val lineHeight = 60f

    @Test
    fun `a positive line number counts text lines down from the top`() {
        assertEquals(180, CaptionGeometry.top(3f, Cue.LINE_TYPE_NUMBER, Cue.ANCHOR_TYPE_START, lineHeight, 60, height))
    }

    @Test
    fun `a negative line number puts the cue's last line that many lines up from the bottom`() {
        // -1 is the bottom line; a two-line cue at -3 ends two lines above the bottom.
        assertEquals(1000 - 120 - 120, CaptionGeometry.top(-3f, Cue.LINE_TYPE_NUMBER, Cue.ANCHOR_TYPE_START, lineHeight, 120, height))
    }

    @Test
    fun `an unset line sits on the bottom`() {
        assertEquals(940, CaptionGeometry.top(Cue.DIMEN_UNSET, Cue.TYPE_UNSET, Cue.TYPE_UNSET, lineHeight, 60, height))
    }

    @Test
    fun `a cue never leaves the frame`() {
        assertEquals(940, CaptionGeometry.top(20f, Cue.LINE_TYPE_NUMBER, Cue.ANCHOR_TYPE_START, lineHeight, 60, height))
        assertEquals(0, CaptionGeometry.top(-30f, Cue.LINE_TYPE_NUMBER, Cue.ANCHOR_TYPE_START, lineHeight, 60, height))
    }

    @Test
    fun `a fractional line honors its anchor`() {
        assertEquals(500, CaptionGeometry.top(0.5f, Cue.LINE_TYPE_FRACTION, Cue.ANCHOR_TYPE_START, lineHeight, 100, height))
        assertEquals(450, CaptionGeometry.top(0.5f, Cue.LINE_TYPE_FRACTION, Cue.ANCHOR_TYPE_MIDDLE, lineHeight, 100, height))
        assertEquals(400, CaptionGeometry.top(0.5f, Cue.LINE_TYPE_FRACTION, Cue.ANCHOR_TYPE_END, lineHeight, 100, height))
    }

    @Test
    fun `horizontal position is taken at the cue's anchor`() {
        assertEquals(192, CaptionGeometry.left(0.1f, Cue.ANCHOR_TYPE_START, 600, width))
        assertEquals(660, CaptionGeometry.left(0.5f, Cue.ANCHOR_TYPE_MIDDLE, 600, width))
        assertEquals(1128, CaptionGeometry.left(0.9f, Cue.ANCHOR_TYPE_END, 600, width))
    }

    @Test
    fun `an unset position centers the cue and a wide cue stays inside the frame`() {
        assertEquals(660, CaptionGeometry.left(Cue.DIMEN_UNSET, Cue.TYPE_UNSET, 600, width))
        assertEquals(1920 - 1800, CaptionGeometry.left(0.9f, Cue.ANCHOR_TYPE_START, 1800, width))
    }
}
