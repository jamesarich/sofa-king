package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptionGeometryTest {
    private val width = 1920
    private val height = 1000
    private val lineHeight = 60f

    @Test
    fun `a positive line number counts text lines down from the top`() {
        assertEquals(
            180,
            CaptionGeometry.top(3f, CaptionCue.LINE_TYPE_NUMBER, CaptionCue.ANCHOR_TYPE_START, lineHeight, 60, height),
        )
    }

    @Test
    fun `a negative line number puts the cue's last line that many lines up from the bottom`() {
        // -1 is the bottom line; a two-line cue at -3 ends two lines above the bottom.
        assertEquals(
            1000 - 120 - 120,
            CaptionGeometry.top(
                -3f,
                CaptionCue.LINE_TYPE_NUMBER,
                CaptionCue.ANCHOR_TYPE_START,
                lineHeight,
                120,
                height,
            ),
        )
    }

    @Test
    fun `an unset line sits on the bottom`() {
        assertEquals(
            940,
            CaptionGeometry.top(
                CaptionCue.DIMEN_UNSET,
                CaptionCue.TYPE_UNSET,
                CaptionCue.TYPE_UNSET,
                lineHeight,
                60,
                height,
            ),
        )
    }

    @Test
    fun `a cue never leaves the frame`() {
        assertEquals(
            940,
            CaptionGeometry.top(20f, CaptionCue.LINE_TYPE_NUMBER, CaptionCue.ANCHOR_TYPE_START, lineHeight, 60, height),
        )
        assertEquals(
            0,
            CaptionGeometry.top(
                -30f,
                CaptionCue.LINE_TYPE_NUMBER,
                CaptionCue.ANCHOR_TYPE_START,
                lineHeight,
                60,
                height,
            ),
        )
    }

    @Test
    fun `a fractional line honors its anchor`() {
        assertEquals(
            500,
            CaptionGeometry.top(
                0.5f,
                CaptionCue.LINE_TYPE_FRACTION,
                CaptionCue.ANCHOR_TYPE_START,
                lineHeight,
                100,
                height,
            ),
        )
        assertEquals(
            450,
            CaptionGeometry.top(
                0.5f,
                CaptionCue.LINE_TYPE_FRACTION,
                CaptionCue.ANCHOR_TYPE_MIDDLE,
                lineHeight,
                100,
                height,
            ),
        )
        assertEquals(
            400,
            CaptionGeometry.top(
                0.5f,
                CaptionCue.LINE_TYPE_FRACTION,
                CaptionCue.ANCHOR_TYPE_END,
                lineHeight,
                100,
                height,
            ),
        )
    }

    @Test
    fun `horizontal position is taken at the cue's anchor`() {
        assertEquals(192, CaptionGeometry.left(0.1f, CaptionCue.ANCHOR_TYPE_START, 600, width))
        assertEquals(660, CaptionGeometry.left(0.5f, CaptionCue.ANCHOR_TYPE_MIDDLE, 600, width))
        assertEquals(1128, CaptionGeometry.left(0.9f, CaptionCue.ANCHOR_TYPE_END, 600, width))
    }

    @Test
    fun `an unset position centers the cue and a wide cue stays inside the frame`() {
        assertEquals(660, CaptionGeometry.left(CaptionCue.DIMEN_UNSET, CaptionCue.TYPE_UNSET, 600, width))
        assertEquals(1920 - 1800, CaptionGeometry.left(0.9f, CaptionCue.ANCHOR_TYPE_START, 1800, width))
    }

    @Test
    fun `a pillarboxed 4 by 3 picture keeps its captions between the pillars`() {
        val rect = CaptionGeometry.pictureRect(IntSize(1920, 1080), IntSize(640, 480), VideoZoom.FIT)
        assertEquals(IntRect(240, 0, 1680, 1080), rect)
    }

    @Test
    fun `a filled or stretched or unknown picture gives the captions the whole box`() {
        val box = IntSize(1920, 1080)
        val whole = IntRect(0, 0, 1920, 1080)
        assertEquals(whole, CaptionGeometry.pictureRect(box, IntSize(640, 480), VideoZoom.FILL))
        assertEquals(whole, CaptionGeometry.pictureRect(box, IntSize(640, 480), VideoZoom.STRETCH))
        assertEquals(whole, CaptionGeometry.pictureRect(box, null, VideoZoom.FIT))
    }

    @Test
    fun `a wide picture in a tall window is letterboxed`() {
        val rect = CaptionGeometry.pictureRect(IntSize(1080, 1920), IntSize(1920, 1080), VideoZoom.FIT)
        assertEquals(IntRect(0, 656, 1080, 1264), rect)
    }
}
