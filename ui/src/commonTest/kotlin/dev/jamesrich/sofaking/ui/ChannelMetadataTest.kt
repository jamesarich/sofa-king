package dev.jamesrich.sofaking.ui

import dev.jamesrich.sofaking.data.Programme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ChannelMetadataTest {
    private fun programme(episode: String? = null, subTitle: String? = null) =
        Programme("c", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(60), "Show", null, subTitle = subTitle, episode = episode)

    @Test
    fun `the episode line puts the label before the episode title`() {
        assertEquals("S2 E5  The Pilot", programme(episode = "S2 E5", subTitle = "The Pilot").episodeLine())
    }

    @Test
    fun `the episode line shows whichever part the guide has`() {
        assertEquals("E5", programme(episode = "E5").episodeLine())
        assertEquals("The Pilot", programme(subTitle = "The Pilot").episodeLine())
    }

    @Test
    fun `a programme without either has no episode line`() {
        assertNull(programme().episodeLine())
    }
}
