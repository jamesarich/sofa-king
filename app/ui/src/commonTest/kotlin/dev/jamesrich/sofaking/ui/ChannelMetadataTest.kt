package dev.jamesrich.sofaking.ui

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.GuideStatus
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_out_of_date
import dev.jamesrich.sofaking.resources.listing_loading
import dev.jamesrich.sofaking.resources.no_guide
import dev.jamesrich.sofaking.resources.no_listing
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

    private fun channel(status: GuideStatus) = Channel("c", 1.0, "One", null, listOf("Local"), status)

    @Test
    fun `a channel without programmes says they are loading until the guide is in`() {
        assertEquals(Res.string.listing_loading, channel(GuideStatus.AVAILABLE).noListingRes(guideLoading = true))
        assertEquals(Res.string.listing_loading, channel(GuideStatus.EMPTY).noListingRes(guideLoading = true))
        assertEquals(Res.string.no_listing, channel(GuideStatus.AVAILABLE).noListingRes(guideLoading = false))
    }

    @Test
    fun `the server's reason for no programmes shows while the guide loads`() {
        assertEquals(Res.string.no_guide, channel(GuideStatus.UNASSIGNED).noListingRes(guideLoading = true))
        assertEquals(Res.string.guide_out_of_date, channel(GuideStatus.STALE).noListingRes(guideLoading = true))
    }
}
