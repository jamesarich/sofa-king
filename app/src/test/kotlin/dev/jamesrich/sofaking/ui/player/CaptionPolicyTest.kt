package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
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
}
