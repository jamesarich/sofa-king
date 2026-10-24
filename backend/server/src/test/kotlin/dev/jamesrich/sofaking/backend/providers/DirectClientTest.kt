package dev.jamesrich.sofaking.backend.providers

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectClientTest {
    @Test fun `a fixed table of broadcaster feeds with static streams and no guide`() = providerTest("sofa-direct-") {
        val client =
            DirectClient(
                context("direct", mapOf("pageBase" to fixture.base)),
            )
        val channels = client.channels()
        assertTrue(channels.size >= 20)
        assertEquals(channels.size, channels.map { it.id }.toSet().size, "ids are unique")
        assertEquals(channels.size, channels.map { it.number }.toSet().size, "numbers are unique")
        assertEquals("cbs-news-247", channels[0].id)
        assertEquals("9101", channels[0].number)
        assertEquals("News", channels[0].group)
        assertEquals(listOf("us"), channels[0].tags)
        assertTrue(channels.all { it.logo == null }, "logos come from the mapped guide")
        assertTrue(
            channels.all {
                it.name.isNotBlank() && it.group!!.isNotBlank() && it.tags.single().length == 2
            },
        )
        assertEquals(listOf("de"), channels.single { it.id == "das-erste" }.tags)
        assertEquals(
            "Euronews",
            channels.single {
                it.id == "euronews"
            }.name,
            "named as the FAST services name it, so the stations merge",
        )
        val resolved = client.resolve("fox-weather")
        assertEquals("https://247wlive.foxweather.com/stream/index.m3u8", resolved.url)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0"), resolved.headers)
        assertNull(resolved.expiresAt, "static URLs do not expire")
        assertEquals(0, fixture.hits.size, "a static feed fetches nothing")
        assertNull(
            client.guide(
                channels.map { it.id }.toSet(),
            ),
            "the feeds carry no guide of their own",
        )
        assertFalse(NativeServices.hasGuide("direct"))
        assertNull(NativeServices.defaultGuideUrl("direct", emptyMap()))
        assertFailsWith<ProviderFailure> { client.resolve("cnn") }.let { assertFalse(it.transient) }
    }

    @Test
    fun `a CBS News feed plays the event URL its live page names, read once per six hours, else the table's URL`() =
        providerTest("sofa-direct-") {
            val client =
                DirectClient(
                    context(
                        "direct",
                        mapOf("pageBase" to fixture.base),
                    ),
                )
            val event = "https://dai.google.com/linear/hls/event/_xQBtL7ZSEClkNebaXzn8w/master.m3u8"
            fixture.text(
                "/chicago/live/",
                """<html><script>var a = {"url":"$event?imafw_csid={imafw_csid}"};</script></html>""",
                "text/html",
            )
            fixture.text("/boston/live/", "<html>no player here</html>", "text/html")
            fixture.route("/miami/live/") { Canned(503, "down".toByteArray(), "text/plain") }
            assertEquals(event, client.resolve("cbs-news-chicago").url)
            assertEquals(event, client.resolve("cbs-news-chicago").url)
            assertEquals(1, fixture.hits("/chicago/live/"), "the page is read once and cached")
            clock.now = clock.now.plus(Duration.ofHours(7))
            client.resolve("cbs-news-chicago")
            assertEquals(2, fixture.hits("/chicago/live/"), "and read again after six hours")
            assertEquals(
                "https://dai.google.com/linear/hls/event/dHA193vxQ_WqZNVoC-W5MA/master.m3u8",
                client.resolve("cbs-news-boston").url,
                "a page without an event URL falls back to the table",
            )
            assertEquals(
                "https://dai.google.com/linear/hls/event/_yODrBHESGSzPe3dqW4gGg/master.m3u8",
                client.resolve("cbs-news-miami").url,
                "an unreachable page falls back to the table",
            )
            assertTrue(
                client.resolve(
                    "cbs-news-247",
                ).url.startsWith("https://cbsn-us.cbsnstream.cbsnews.com/"),
                "the national stream falls back to CBS's own CDN",
            )
        }

    @Test fun `streamBase points every feed at another host`() = providerTest("sofa-direct-") {
        val client =
            DirectClient(
                context("direct", mapOf("streamBase" to "${fixture.base}/direct/")),
            )
        assertEquals("${fixture.base}/direct/newsmax.m3u8", client.resolve("newsmax").url)
        assertEquals(
            "${fixture.base}/direct/cbs-news-chicago.m3u8",
            client.resolve("cbs-news-chicago").url,
        )
        assertEquals(0, fixture.hits.size)
    }
}
