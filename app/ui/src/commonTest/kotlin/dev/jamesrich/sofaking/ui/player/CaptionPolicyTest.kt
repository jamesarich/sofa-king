package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionPolicyTest {

    @Test
    fun `toggling on a channel with captions announces on`() {
        assertEquals(CaptionToggleOutcome.ON, CaptionPolicy.toggleOutcome(enabledAfter = true, channelHasText = true))
    }

    @Test
    fun `toggling off a channel with captions announces off`() {
        assertEquals(CaptionToggleOutcome.OFF, CaptionPolicy.toggleOutcome(enabledAfter = false, channelHasText = true))
    }

    @Test
    fun `a channel without captions reports that whichever way the toggle goes`() {
        assertEquals(
            CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL,
            CaptionPolicy.toggleOutcome(enabledAfter = true, channelHasText = false),
        )
        assertEquals(
            CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL,
            CaptionPolicy.toggleOutcome(enabledAfter = false, channelHasText = false),
        )
    }

    @Test
    fun `bottom padding is the 27dp overscan as a fraction of the screen height`() {
        assertEquals(27f / 1080f, CaptionPolicy.bottomPaddingFraction(1080), 0.0001f)
        assertEquals(27f / 720f, CaptionPolicy.bottomPaddingFraction(720), 0.0001f)
    }

    @Test
    fun `bottom padding rejects a zero height`() {
        assertFailsWith<IllegalArgumentException> { CaptionPolicy.bottomPaddingFraction(0) }
    }

    private fun track(group: Int, mime: String?, channel: Int = 1) =
        TextTrackInfo(groupIndex = group, trackIndex = 0, mimeType = mime, accessibilityChannel = channel)

    @Test
    fun `cea-708 service one is preferred over cea-608 when both are present`() {
        val tracks = listOf(track(0, CaptionPolicy.MIME_CEA608), track(1, CaptionPolicy.MIME_CEA708))
        assertEquals(tracks[1], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `cea-708 on another service is taken before cea-608 CC1`() {
        val tracks = listOf(track(0, CaptionPolicy.MIME_CEA608), track(1, CaptionPolicy.MIME_CEA708, channel = 3))
        assertEquals(tracks[1], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `cea-608 CC1 is used when there is no cea-708 track such as after an ad break`() {
        val tracks = listOf(track(0, CaptionPolicy.MIME_CEA608, channel = 3), track(1, CaptionPolicy.MIME_CEA608))
        assertEquals(tracks[1], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `a non-caption text track is used only when no caption track exists`() {
        val tracks = listOf(track(0, "text/vtt"), track(1, null))
        assertEquals(tracks[0], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `no text tracks means no preferred track`() {
        assertNull(CaptionPolicy.preferredTextTrack(emptyList()))
    }

    @Test
    fun `advance is labelled in seconds with one decimal and no sign`() {
        assertEquals("3.0", CaptionPolicy.advanceLabel(3_000))
        assertEquals("3.5", CaptionPolicy.advanceLabel(3_500))
        assertEquals("0.0", CaptionPolicy.advanceLabel(0))
        assertEquals("8.0", CaptionPolicy.advanceLabel(8_000))
        assertEquals("2.5", CaptionPolicy.advanceLabel(-2_500))
    }

    @Test
    fun `the sign of a timing says whether captions show earlier or later`() {
        assertEquals(CaptionTiming.EARLIER, CaptionPolicy.timingDirection(500))
        assertEquals(CaptionTiming.LATER, CaptionPolicy.timingDirection(-500))
        assertEquals(CaptionTiming.AS_BROADCAST, CaptionPolicy.timingDirection(0))
    }

    @Test
    fun `a single step moves half a second and is clamped to eight seconds either way`() {
        assertEquals(3_500, CaptionPolicy.stepAdvance(3_000, direction = 1))
        assertEquals(2_500, CaptionPolicy.stepAdvance(3_000, direction = -1))
        assertEquals(-500, CaptionPolicy.stepAdvance(0, direction = -1))
        assertEquals(0, CaptionPolicy.stepAdvance(-500, direction = 1))
        assertEquals(8_000, CaptionPolicy.stepAdvance(8_000, direction = 1))
        assertEquals(-8_000, CaptionPolicy.stepAdvance(-8_000, direction = -1))
        assertEquals(-8_000..8_000, AppSettings.CAPTION_ADVANCE_RANGE_MS)
    }
}
