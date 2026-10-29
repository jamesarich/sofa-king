package dev.jamesrich.sofaking.backend.providers

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZeamClientTest {
    private fun ProviderFixture.zeam() {
        text("/live", ProviderFixture.resource("zeam-live.html").replace("BASE", base), "text/html")
        json("/api/services/StreamInfo", ProviderFixture.resource("zeam-streaminfo.json").replace("BASE", base))
        json("/sched/16049.json", ProviderFixture.resource("zeam-schedule-16049.json"))
        json("/sched/20001.json", ProviderFixture.resource("zeam-schedule-20001.json"))
    }

    private fun ProviderScope.zeam() = ZeamClient(context("zeam", mapOf("siteBase" to fixture.base)))

    @Test fun `lists the All Channels group once each with its largest square logo`() = providerTest("sofa-zeam-") {
        fixture.zeam()
        val client = zeam()
        val channels = client.channels()
        assertEquals(listOf("16049", "20001", "20002"), channels.map { it.id }, "no duplicate, no row without an id")
        val air = channels.first()
        assertEquals("Air 4 Adventures", air.name)
        assertEquals("https://cdn.example/prod/sized/18752/256x256.png", air.logo, "typeId 1, largest, with a scheme")
        assertEquals("https://cdn.example/prod/sized/1/128x128.png", channels[1].logo)
        assertNull(channels[2].logo)
        assertEquals(listOf("us"), air.tags)
        assertEquals(BROWSER_USER_AGENT, fixture.lastHeaders["/live"]!!["user-agent"])
        client.channels()
        client.guide(emptySet())
        assertEquals(1, fixture.hits("/live"), "the listing is shared")
    }

    @Test fun `resolve asks StreamInfo for the station and pins the master's User-Agent for every request`() =
        providerTest("sofa-zeam-") {
            fixture.zeam()
            val resolved = zeam().resolve("16049")
            assertEquals("${fixture.base}/cpl/17259797/dai2v6/1.0/master.m3u8?access_token=fixture", resolved.url)
            assertEquals(mapOf("User-Agent" to BROWSER_USER_AGENT), resolved.headers)
            assertEquals(clock.now.plus(Duration.ofSeconds(90)), resolved.expiresAt)
            assertEquals("stationId=16049", fixture.lastQuery["/api/services/StreamInfo"])
            assertEquals(BROWSER_USER_AGENT, fixture.lastHeaders["/api/services/StreamInfo"]!!["user-agent"])
        }

    @Test fun `a channel gone from the lineup fails permanently without asking StreamInfo`() = providerTest(
        "sofa-zeam-",
    ) {
        fixture.zeam()
        val failure = assertFailsWith<ProviderFailure> { zeam().resolve("99999") }
        assertFalse(failure.transient)
        assertEquals(0, fixture.hits("/api/services/StreamInfo"))
        assertEquals(2, fixture.hits("/live"), "an unknown id reads the lineup again once")
    }

    @Test fun `guide cuts nested rows, drops spent and unusable ones and keeps the sub-title`() = providerTest(
        "sofa-zeam-",
    ) {
        fixture.zeam()
        val guide = zeam().guide(setOf("16049"))
        assertEquals(3, guide.channels.size)
        assertEquals(0, fixture.hits("/sched/20001.json"), "only wanted channels are fetched")
        assertEquals(
            listOf(
                "Air-4 Adventures from KXLY 2026-10-10T13:00:00Z 2026-10-10T14:00:00Z",
                "Air-4 Adventures from KXLY 2026-10-10T14:00:00Z 2026-10-10T14:30:00Z",
                "Nested Block 2026-10-10T14:30:00Z 2026-10-10T15:10:00Z",
                "Duration Only 2026-10-10T15:30:00Z 2026-10-10T15:45:00Z",
            ),
            guide.programs.map { "${it.title} ${it.startsAt} ${it.endsAt}" },
            "spent, contained, unnamed, empty and blacked-out rows are dropped",
        )
        assertTrue(guide.programs.all { it.channelId == "16049" })
        val (first, second) = guide.programs
        assertEquals("Air 4 Adventure: Centennial Trail", first.subTitle)
        assertEquals("The Centennial Trail is nearly 65 miles long.", first.description)
        assertNull(second.subTitle, "an episode title equal to the title says nothing")
    }

    @Test fun `a schedule the CDN no longer serves is that channel without a guide`() = providerTest("sofa-zeam-") {
        fixture.zeam()
        val programs = zeam().guide(setOf("20001", "20002")).programs
        assertEquals(listOf("KGW News"), programs.map { it.title })
        assertEquals(1, fixture.hits("/sched/20002.json"))
    }

    @Test fun `a failing service is transient`() = providerTest("sofa-zeam-") {
        fixture.zeam()
        fixture.route("/api/services/StreamInfo") { Canned("oops", 500, "text/plain") }
        fixture.route("/sched/16049.json") { Canned("oops", 503, "text/plain") }
        val client = zeam()
        assertTrue(assertFailsWith<ProviderFailure> { client.resolve("16049") }.transient)
        assertTrue(assertFailsWith<ProviderFailure> { client.guide(setOf("16049")) }.transient)
        fixture.route("/live") { Canned("down", 503, "text/plain") }
        val down = ZeamClient(context("zeam-down", mapOf("siteBase" to fixture.base)))
        assertTrue(assertFailsWith<ProviderFailure> { down.channels() }.transient)
        fixture.text("/live", "<html>no data here</html>", "text/html")
        val changed = ZeamClient(context("zeam-changed", mapOf("siteBase" to fixture.base)))
        assertTrue(assertFailsWith<ProviderFailure> { changed.channels() }.transient)
    }

    @Test fun `failures name neither the token nor the URL`() = providerTest("sofa-zeam-") {
        fixture.zeam()
        fixture.json("/api/services/StreamInfo", """{"streamUrl": null}""")
        val failure = assertFailsWith<ProviderFailure> { zeam().resolve("16049") }
        assertEquals("Zeam stream info has no stream", failure.message)
        assertTrue(failure.transient)
    }
}
