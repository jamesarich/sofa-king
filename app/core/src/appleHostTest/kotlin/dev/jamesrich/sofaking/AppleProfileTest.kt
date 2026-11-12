package dev.jamesrich.sofaking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleProfileTest {
    @Test
    fun `the probe only adds AV1 to what AVPlayer plays everywhere`() {
        val added = appleTvProfile.videoCodecs - appleBaseProfile.videoCodecs.toSet()
        assertTrue(added == emptyList<String>() || added == listOf("av1"), "added $added")
        assertTrue(appleTvProfile.videoCodecs.containsAll(appleBaseProfile.videoCodecs))
        assertEquals(
            appleBaseProfile,
            appleTvProfile.copy(videoCodecs = listOf("h264", "hevc"), deepColor = listOf("hevc")),
        )
    }
}
