package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CwClientTest {
    private val feedPath = "/feed/page_1/pagesize_75/device_web/apiversion_24/cacheversion_" +
        "${java.time.Instant.parse("2026-10-10T13:00:00Z").epochSecond / 900}"

    private fun ProviderScope.cw(): CwClient = CwClient(context("cw", mapOf("feedBase" to "${fixture.base}/feed")))

    @Test fun `lists clear HLS channels and skips DRM ones`() = providerTest("sofa-cw-") {
        fixture.json(feedPath, ProviderFixture.resource("cw-feed.json"))
        val channels = cw().channels()
        assertEquals(listOf("cw-forever", "ion", "court-tv", "filmrise-drama", "tba-sample"), channels.map { it.id })
        val forever = channels.first()
        assertEquals("CW Forever", forever.name)
        assertEquals("Entertainment", forever.group)
        assertEquals(listOf("us"), forever.tags)
        assertEquals(
            "https://static.cwtv.com/images/masters/cw/universal/channels/cw-forever/channel-thumbnail.jpg",
            forever.logo,
        )
        assertEquals("Crime", channels.first { it.id == "court-tv" }.group, "the first of a comma-separated list")
    }

    @Test fun `resolve returns the listed URL from the shared fetch`() = providerTest("sofa-cw-") {
        fixture.json(feedPath, ProviderFixture.resource("cw-feed.json"))
        val client = cw()
        val resolved = client.resolve("cw-forever")
        assertEquals(
            "https://cdn-uw2-prod.tsv2.amagi.tv/linear/amg01312-nexstarmediagro-cwforever-nondrmhlstsv2-cw/" +
                "playlist.m3u8",
            resolved.url,
        )
        assertNull(resolved.expiresAt)
        assertTrue(resolved.headers.isEmpty())
        client.channels()
        client.guide(setOf("ion"))
        assertEquals(1, fixture.hits(feedPath), "channels, resolve and the guide share one fetch")
    }

    @Test fun `guide maps a wanted channel's programs and indexes every channel`() = providerTest("sofa-cw-") {
        fixture.json(feedPath, ProviderFixture.resource("cw-feed.json"))
        val guide = cw().guide(setOf("cw-forever", "tba-sample"))
        assertEquals(5, guide.channels.size)
        assertEquals(3, guide.programs.size, "the To Be Announced filler is dropped")
        val first = guide.programs.first()
        assertEquals("cw-forever", first.channelId)
        assertEquals("Beauty and the Beast", first.title)
        assertEquals("2026-10-28T22:11:36Z", first.startsAt.toString())
        assertEquals("2026-10-28T23:00:50Z", first.endsAt.toString())
        assertEquals(ProgramRating("TV-PG", "VCHIP"), first.rating)
        assertEquals(listOf("Action"), first.categories)
        assertTrue(first.description!!.startsWith("In a surprising turn"))
        assertTrue(cw().guide(emptySet()).programs.isEmpty())
    }

    @Test fun `a failing service is a transient failure`() = providerTest("sofa-cw-") {
        fixture.route(feedPath) { Canned("down", status = 503, contentType = "text/plain") }
        val client = cw()
        assertTrue(assertFailsWith<ProviderFailure> { client.channels() }.transient)
        fixture.json(feedPath, """{"result": "ok", "channels": []}""")
        val empty = assertFailsWith<ProviderFailure> { client.resolve("ion") }
        assertTrue(empty.transient, "an empty feed is the service's fault, not a gone channel")
        assertFalse(empty.message!!.contains("http"), "no URL in the message")
    }

    @Test fun `a channel gone from the feed fails permanently`() = providerTest("sofa-cw-") {
        fixture.json(feedPath, ProviderFixture.resource("cw-feed.json"))
        val client = cw()
        assertFalse(assertFailsWith<ProviderFailure> { client.resolve("gone-channel") }.transient)
        assertEquals(2, fixture.hits(feedPath), "a miss reads the feed once more before giving up")
        assertFalse(assertFailsWith<ProviderFailure> { client.resolve("drm-sample") }.transient)
    }
}
