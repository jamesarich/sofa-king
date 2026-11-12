package dev.jamesrich.sofaking

import kotlin.test.Test
import kotlin.test.assertEquals

class WebProfileTest {
    @Test
    fun `with no document to ask the profile is the one every browser plays`() {
        assertEquals(webBaseProfile, webProfile)
        assertEquals(listOf("h264", "vp8", "vp9"), webProfile.videoCodecs)
    }
}
