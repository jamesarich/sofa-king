package dev.jamesrich.sofaking.ui.player

import android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
import android.view.Display.HdrCapabilities.HDR_TYPE_HLG
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProfileTest {
    private fun profile(
        decoders: Set<String> = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265),
        maxHeight: Int? = 2160,
        hevcHdr10: Boolean = true,
        displayHdrTypes: IntArray = intArrayOf(HDR_TYPE_HDR10),
        deep: Set<String> = setOf(MimeTypes.VIDEO_H265),
    ) = playbackProfileOf(decoders::contains, maxHeight, hevcHdr10, displayHdrTypes, deep::contains)

    @Test
    fun `codecs are the ones a decoder takes with H264 always`() {
        val profile = profile(decoders = setOf(MimeTypes.VIDEO_VP9, MimeTypes.AUDIO_E_AC3))
        assertEquals(listOf("h264", "vp9"), profile.videoCodecs)
        assertEquals(listOf("aac", "mp3", "opus", "vorbis", "flac", "eac3"), profile.audioCodecs)
    }

    @Test
    fun `the height is the tallest a decoder takes up to 2160 or 1080 when unknown`() {
        assertEquals(2160, profile(maxHeight = 4320).maxHeight)
        assertEquals(1088, profile(maxHeight = 1088).maxHeight)
        assertEquals(1080, profile(maxHeight = null).maxHeight)
    }

    @Test
    fun `HDR needs an HDR10 display and an HEVC decoder with the HDR10 profile`() {
        assertTrue(profile().hdr)
        assertFalse(profile(hevcHdr10 = false).hdr)
        assertFalse(profile(displayHdrTypes = intArrayOf()).hdr)
        assertFalse(profile(displayHdrTypes = intArrayOf(HDR_TYPE_DOLBY_VISION, HDR_TYPE_HLG)).hdr)
    }

    @Test
    fun `deep color lists the codecs a decoder takes at 10 bits`() {
        assertEquals(listOf("hevc"), profile().deepColor)
        assertEquals(emptyList<String>(), profile(deep = emptySet()).deepColor)
        assertEquals("no VP9 decoder at all", listOf("h264"), profile(deep = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9)).deepColor)
    }
}
