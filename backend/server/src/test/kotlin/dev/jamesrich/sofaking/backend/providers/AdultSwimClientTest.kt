package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdultSwimClientTest {
    @Test fun `streams come from the page state, assets from the video API and the guide from the marathon schedule`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-adultswim-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.text("/streams", ProviderFixture.resource("adultswim-streams.html"), "text/html")
            fixture.json("/api/shows/v1/videos/jqX79_YeQkm3I9kHJYGAXA", ProviderFixture.resource("adultswim-video.json").replace("BASE", fixture.base))
            fixture.json("/api/schedule/marathons/5801264b1bb9661440d5f845", ProviderFixture.resource("adultswim-schedule.json"))
            val clock = MutableClock()
            val client = AdultSwimClient(ProviderFixture.context("adultswim", mapOf("siteBase" to fixture.base), http, store, clock))
            val channels = client.channels()
            assertEquals(listOf("rick-and-morty", "aqua-teen-hunger-force"), channels.map { it.id }, "a row without a video id is skipped")
            assertEquals("Rick and Morty", channels[0].name); assertEquals("1", channels[0].number); assertEquals("Comedy", channels[0].group)
            assertTrue(channels[0].logo!!.startsWith("https://media.cdn.adultswim.com/"))
            assertEquals(0, fixture.hits("/api/shows/v1/videos/jqX79_YeQkm3I9kHJYGAXA"), "assets are looked up on resolve, not on every import")
            val resolved = client.resolve("rick-and-morty")
            assertEquals("${fixture.base}/adultswim/live/rick-and-morty/stream.m3u8", resolved.url, "the live HLS asset, not the VOD mp4")
            assertEquals("fields=title,type,duration,collection_title,poster,stream,title_id,auth,media_id,launch_date,auth_launch_date,disable_ads,season_number,episode_number", fixture.lastQuery["/api/shows/v1/videos/jqX79_YeQkm3I9kHJYGAXA"])
            client.resolve("rick-and-morty")
            assertEquals(1, fixture.hits("/api/shows/v1/videos/jqX79_YeQkm3I9kHJYGAXA"), "the asset is cached")
            val guide = client.guide(setOf("rick-and-morty"))
            assertEquals(2, guide.channels.size)
            assertEquals(listOf("Rick and Morty", "Rick and Morty"), guide.programmes.map { it.title }, "the series is the title")
            assertEquals(listOf("Promortyus", "The Vat of Acid Episode"), guide.programmes.map { it.subTitle }, "the episode is the sub-title")
            assertNull(guide.programmes[0].description)
            assertEquals("2026-10-11T02:38:26.519Z", guide.programmes[0].startsAt.toString())
            assertEquals("2026-10-11T02:59:41.092Z", guide.programmes[0].endsAt.toString(), "start plus the fractional duration")
            assertFailsWith<ProviderFailure> { client.resolve("aqua-teen-hunger-force") }.let { assertFalse(it.transient, "a 404 from the video API means the asset is gone") }
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}
