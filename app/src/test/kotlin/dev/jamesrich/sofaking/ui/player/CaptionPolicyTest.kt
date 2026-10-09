package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import dev.jamesrich.sofaking.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionPolicyTest {

    @Test
    fun `a text track among other tracks counts as captions`() {
        assertTrue(CaptionPolicy.hasTextTrack(listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)))
    }

    @Test
    fun `a channel without a text track has no captions`() {
        assertFalse(CaptionPolicy.hasTextTrack(listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)))
        assertFalse(CaptionPolicy.hasTextTrack(emptyList()))
    }

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

    @Test(expected = IllegalArgumentException::class)
    fun `bottom padding rejects a zero height`() {
        CaptionPolicy.bottomPaddingFraction(0)
    }

    private fun track(group: Int, mime: String?, channel: Int = 1) =
        TextTrackInfo(groupIndex = group, trackIndex = 0, mimeType = mime, accessibilityChannel = channel)

    @Test
    fun `cea-608 CC1 is preferred over cea-708 when both are present`() {
        val tracks = listOf(track(0, MimeTypes.APPLICATION_CEA708), track(1, MimeTypes.APPLICATION_CEA608))
        assertEquals(tracks[1], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `cea-708 service one is used when there is no cea-608 track`() {
        val tracks = listOf(track(0, MimeTypes.APPLICATION_CEA708, channel = 3), track(1, MimeTypes.APPLICATION_CEA708))
        assertEquals(tracks[1], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `cea-608 on another channel is taken before cea-708`() {
        val tracks = listOf(track(0, MimeTypes.APPLICATION_CEA708), track(1, MimeTypes.APPLICATION_CEA608, channel = 3))
        assertEquals(tracks[1], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `a non-caption text track is used only when no caption track exists`() {
        val tracks = listOf(track(0, MimeTypes.TEXT_VTT), track(1, null))
        assertEquals(tracks[0], CaptionPolicy.preferredTextTrack(tracks))
    }

    @Test
    fun `no text tracks means no preferred track`() {
        assertNull(CaptionPolicy.preferredTextTrack(emptyList()))
    }

    @Test
    fun `advance is labelled in seconds with one decimal`() {
        assertEquals("3.0", CaptionPolicy.advanceLabel(3_000))
        assertEquals("3.5", CaptionPolicy.advanceLabel(3_500))
        assertEquals("0.0", CaptionPolicy.advanceLabel(0))
        assertEquals("8.0", CaptionPolicy.advanceLabel(8_000))
    }

    @Test
    fun `a single step moves half a second and is clamped to the range`() {
        assertEquals(3_500, CaptionPolicy.stepAdvance(3_000, direction = 1))
        assertEquals(2_500, CaptionPolicy.stepAdvance(3_000, direction = -1))
        assertEquals(AppSettings.CAPTION_ADVANCE_RANGE_MS.last, CaptionPolicy.stepAdvance(8_000, direction = 1))
        assertEquals(AppSettings.CAPTION_ADVANCE_RANGE_MS.first, CaptionPolicy.stepAdvance(0, direction = -1))
    }
}
