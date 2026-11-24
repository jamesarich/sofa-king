package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackProfileTest {
    private val base = PlaybackProfile(
        containers = listOf("mp4"),
        videoCodecs = listOf("h264", "hevc"),
        audioCodecs = listOf("aac"),
        maxHeight = 2160,
        hdr = true,
        deepColor = listOf("hevc"),
        wholePlaylist = true,
    )

    @Test
    fun `a probed codec is added for 8 and 10 bits and everything else stays`() {
        val probed = base.alsoDecoding(listOf("av1"))
        assertEquals(listOf("h264", "hevc", "av1"), probed.videoCodecs)
        assertEquals(listOf("hevc", "av1"), probed.deepColor)
        assertEquals(base, probed.copy(videoCodecs = base.videoCodecs, deepColor = base.deepColor))
    }

    @Test
    fun `a probe that finds nothing or repeats a codec leaves the profile as it was`() {
        assertEquals(base, base.alsoDecoding(emptyList()))
        assertEquals(base, base.alsoDecoding(listOf("hevc")))
    }

    @Test
    fun `a profile that leaves 10 bits to the server still does`() {
        val web = PlaybackProfile(videoCodecs = listOf("h264", "vp9"))
        assertNull(web.alsoDecoding(listOf("av1")).deepColor)
        assertEquals(listOf("h264", "vp9", "av1"), web.alsoDecoding(listOf("av1")).videoCodecs)
    }
}
