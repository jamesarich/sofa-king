package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The format mappers; the track list and its ids need a `TrackGroup`, which calls Android's `TextUtils`. */
class AudioTracksTest {
    @Test
    fun `codec names read the codecs string first and the MIME type after`() {
        assertEquals("H.264", codecName("avc1.64001f", MimeTypes.VIDEO_H265))
        assertEquals("H.264", codecName("avc1.64001f,mp4a.40.2", null))
        assertEquals("HEVC", codecName("hvc1.2.4.L153.B0", null))
        assertEquals("AAC", codecName("mp4a.40.2", null))
        assertEquals("AC-3", codecName(null, MimeTypes.AUDIO_AC3))
        assertEquals("FLAC", codecName(null, MimeTypes.AUDIO_FLAC))
        assertEquals("xyz1", codecName("xyz1", null))
        assertNull(codecName(null, null))
    }

    @Test
    fun `stream info reads the chosen formats and leaves out what they do not say`() {
        val video = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.64001f")
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(29.97f)
            .setPeakBitrate(8_000_000)
            .build()
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()
        assertEquals(StreamInfo("H.264", 1920, 1080, 29.97f, 8_000_000, "E-AC-3"), streamInfoOf(video, audio))
        assertEquals(StreamInfo(), streamInfoOf(Format.Builder().build(), null))
        assertEquals(StreamInfo(), streamInfoOf(null, null))
    }
}
