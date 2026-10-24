package dev.jamesrich.sofaking.backend.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VIDEO = "/api/shows/v1/videos/jqX79_YeQkm3I9kHJYGAXA"

class AdultSwimClientTest {
    /** The client over the fixture's streams page, video API and marathon schedule. */
    private fun ProviderScope.adultSwim(): AdultSwimClient {
        fixture.text("/streams", ProviderFixture.resource("adultswim-streams.html"), "text/html")
        fixture.json(VIDEO, ProviderFixture.resource("adultswim-video.json").replace("BASE", fixture.base))
        fixture.json(
            "/api/schedule/marathons/5801264b1bb9661440d5f845",
            ProviderFixture.resource("adultswim-schedule.json"),
        )
        return AdultSwimClient(context("adultswim", mapOf("siteBase" to fixture.base)))
    }

    @Test fun `streams come from the page state, without looking up their assets`() = providerTest("sofa-adultswim-") {
        val channels = adultSwim().channels()
        assertEquals(
            listOf("rick-and-morty", "aqua-teen-hunger-force"),
            channels.map { it.id },
            "a row without a video id is skipped",
        )
        assertEquals("Rick and Morty", channels[0].name)
        assertEquals("1", channels[0].number)
        assertEquals("Comedy", channels[0].group)
        assertTrue(channels[0].logo!!.startsWith("https://media.cdn.adultswim.com/"))
        assertEquals(0, fixture.hits(VIDEO), "assets are looked up on resolve, not on every import")
    }

    @Test fun `assets come from the video API, once per stream`() = providerTest("sofa-adultswim-") {
        val client = adultSwim()
        client.channels()
        val resolved = client.resolve("rick-and-morty")
        assertEquals(
            "${fixture.base}/adultswim/live/rick-and-morty/stream.m3u8",
            resolved.url,
            "the live HLS asset, not the VOD mp4",
        )
        assertEquals(
            "fields=title,type,duration,collection_title,poster,stream,title_id,auth,media_id," +
                "launch_date,auth_launch_date,disable_ads,season_number,episode_number",
            fixture.lastQuery[VIDEO],
        )
        client.resolve("rick-and-morty")
        assertEquals(1, fixture.hits(VIDEO), "the asset is cached")
        assertFailsWith<ProviderFailure> {
            client.resolve("aqua-teen-hunger-force")
        }.let { assertFalse(it.transient, "a 404 from the video API means the asset is gone") }
    }

    @Test fun `the guide comes from the marathon schedule`() = providerTest("sofa-adultswim-") {
        val client = adultSwim()
        client.channels()
        val guide = client.guide(setOf("rick-and-morty"))
        assertEquals(2, guide.channels.size)
        assertEquals(
            listOf("Rick and Morty", "Rick and Morty"),
            guide.programs.map {
                it.title
            },
            "the series is the title",
        )
        assertEquals(
            listOf("Promortyus", "The Vat of Acid Episode"),
            guide.programs.map { it.subTitle },
            "the episode is the sub-title",
        )
        assertNull(guide.programs[0].description)
        assertEquals("2026-10-11T02:38:26.519Z", guide.programs[0].startsAt.toString())
        assertEquals(
            "2026-10-11T02:59:41.092Z",
            guide.programs[0].endsAt.toString(),
            "start plus the fractional duration",
        )
    }
}
