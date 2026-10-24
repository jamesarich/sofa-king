package dev.jamesrich.sofaking.backend.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BallySportsClientTest {
    @Test fun `channels and guide come out of the RSC chunks of the channel page`() = providerTest("sofa-bally-") {
        fixture.route("/channels/2-milb") {
            Canned(status = 307, location = "${fixture.base}/channels/milb")
        }
        fixture.text(
            "/channels/milb",
            ProviderFixture.resource("ballysports-page.html"),
            "text/html; charset=utf-8",
        )
        val client =
            BallySportsClient(
                context("bally", mapOf("pageUrl" to "${fixture.base}/channels/2-milb")),
            )
        val channels = client.channels()
        assertEquals(
            listOf("2", "15"),
            channels.map { it.id },
            "a channel off the Bally CDN is left out",
        )
        assertEquals("MiLB", channels[0].name)
        assertEquals("1", channels[0].number)
        assertEquals("Sports", channels[0].group)
        assertTrue(channels[0].logo!!.endsWith("Horizontal.png"))
        assertNull(channels[1].logo)
        assertEquals(1, fixture.hits("/channels/milb"))
        assertEquals(
            "https://fast01.channels.ballys.tv/abr_default/index_dvr.m3u8",
            client.resolve("15").url,
        )
        assertNull(client.resolve("15").expiresAt)
        val guide = client.guide(setOf("2"))
        assertEquals(2, guide.channels.size)
        val program = guide.programs.single()
        assertEquals("Eli Willits with a three-RBI game", program.title)
        assertEquals("Nationals No. 1 prospect", program.description)
        assertEquals(
            "2026-10-10T03:00:00Z",
            program.startsAt.toString(),
            "the \$D date prefix is stripped",
        )
        assertEquals(
            1,
            fixture.hits("/channels/milb"),
            "one page fetch serves channels, resolve and guide",
        )
    }

    @Test fun `a page without the payload fails the import instead of emptying it`() {
        val (channels, epg) = BallySportsClient.parse(
            "<html><script>self.__next_f.push([1,\"1a:{\\\"other\\\":[1,2]}\"])</script></html>",
        )
        assertTrue(channels.isEmpty() && epg.isEmpty())
    }
}
