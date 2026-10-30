package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.providers.Canned
import dev.jamesrich.sofaking.backend.providers.ProviderFixture
import dev.jamesrich.sofaking.backend.providers.ProviderHttp
import dev.jamesrich.sofaking.backend.providers.ProviderScope
import dev.jamesrich.sofaking.backend.providers.providerTest
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal val FILES = listOf("channels", "logos", "blocklist", "timezones", "streams")

internal fun ProviderScope.serveIptvOrg() {
    for (name in FILES) fixture.json("/api/$name.json", ProviderFixture.resource("iptv-org/$name.json"))
}

internal fun ProviderScope.iptvOrgCatalog(
    directory: Path,
    settings: () -> IptvOrgSettings,
    zone: ZoneId = ZoneId.of("Europe/London"),
    changed: () -> Unit = {},
): IptvOrgCatalog = IptvOrgCatalog(
    directory,
    ProviderHttp(http, UpstreamPolicy(allowLoopback = true)),
    clock,
    settings,
    Jobs(clock),
    baseUrl = "${fixture.base}/api",
    zone = { zone },
    onChanged = changed,
)

class IptvOrgCatalogTest {
    @Test fun `downloads the metadata files and the streams only when streams are on`() = providerTest("iptv-org-") {
        serveIptvOrg()
        val dir = Files.createTempDirectory("iptv-org-data")
        val catalog = iptvOrgCatalog(dir, { IptvOrgSettings(metadata = true) })
        val outcome = catalog.refresh()
        assertTrue(outcome.ok, outcome.note)
        assertEquals(7, catalog.data.channels.size)
        assertEquals("https://logos.example/cnn.png", catalog.data.logos["CNN.us"], "the logo in use wins")
        assertEquals("dmca", catalog.data.blocklist.getValue("Blocked.us").reason)
        assertEquals("GB", catalog.data.timezones["Europe/London"])
        assertEquals(0, fixture.hits("/api/streams.json"), "metadata alone never asks for streams")
        assertTrue(catalog.data.streams.isEmpty())

        val withStreams = iptvOrgCatalog(dir, { IptvOrgSettings(streams = true) })
        withStreams.load()
        assertTrue(withStreams.refresh().ok)
        assertEquals(1, fixture.hits("/api/streams.json"))
        assertEquals(8, withStreams.data.streams.size, "a stream with no channel is not listed")
    }

    @Test fun `asks again with the validators and keeps the copy on not modified`() = providerTest("iptv-org-") {
        serveIptvOrg()
        val seen = AtomicInteger()
        for (name in FILES) {
            fixture.route("/api/$name.json") { exchange ->
                if (exchange.requestHeaders.getFirst("If-None-Match") == "\"v1\"") {
                    seen.incrementAndGet()
                    Canned(status = 304)
                } else {
                    exchange.responseHeaders.set("ETag", "\"v1\"")
                    Canned(ProviderFixture.resource("iptv-org/$name.json"))
                }
            }
        }
        val changes = AtomicInteger()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { IptvOrgSettings(metadata = true) }) {
            changes.incrementAndGet()
        }
        catalog.refresh()
        assertEquals(1, changes.get())
        val second = catalog.refresh()
        assertTrue(second.ok, second.note)
        assertEquals(4, seen.get(), "the second download sent If-None-Match for each of the four files")
        assertEquals(1, changes.get(), "an unchanged file reports no change")
        assertEquals(7, catalog.data.channels.size)
        assertTrue("channels.json: unchanged" in second.note.orEmpty(), second.note)
    }

    @Test fun `keeps the last good copy when a download fails`() = providerTest("iptv-org-") {
        serveIptvOrg()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { IptvOrgSettings(metadata = true) })
        catalog.refresh()
        fixture.route("/api/channels.json") { Canned("down", 503, "text/plain") }
        val outcome = catalog.refresh()
        assertFalse(outcome.ok)
        assertTrue("channels.json: HTTP 503" in outcome.note.orEmpty(), outcome.note)
        assertEquals(7, catalog.data.channels.size, "the last good copy stays in use")
        assertEquals(1, catalog.data.blocklist.size)
    }

    @Test fun `refuses a channel list that lost more than half of its channels`() = providerTest("iptv-org-") {
        serveIptvOrg()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { IptvOrgSettings(metadata = true) })
        catalog.refresh()
        fixture.json(
            "/api/channels.json",
            """[{"id":"CNN.us","name":"CNN","country":"US","categories":["news"]},""" +
                """{"id":"BBCOne.uk","name":"BBC One","country":"GB"},{"id":"X.us","name":"X","country":"US"}]""",
        )
        val outcome = catalog.refresh()
        assertFalse(outcome.ok)
        assertTrue("refused a download of 3 entries after 7" in outcome.note.orEmpty(), outcome.note)
        assertEquals(7, catalog.data.channels.size)
        catalog.load()
        assertEquals(7, catalog.data.channels.size, "the copy on disk is the good one")
    }

    @Test fun `makes no request while both parts are off`() = providerTest("iptv-org-") {
        serveIptvOrg()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { IptvOrgSettings() })
        val outcome = catalog.refresh()
        assertTrue(outcome.ok)
        assertEquals("off", outcome.note)
        for (name in FILES) assertEquals(0, fixture.hits("/api/$name.json"), name)
        assertTrue(catalog.data.channels.isEmpty())
    }

    @Test fun `is due again a week after a download and six hours after a failed one`() = providerTest("iptv-org-") {
        serveIptvOrg()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { IptvOrgSettings(metadata = true) })
        assertTrue(catalog.due(), "never downloaded")
        catalog.refresh()
        assertFalse(catalog.due())
        clock.now = clock.now.plus(Duration.ofDays(6))
        assertFalse(catalog.due())
        clock.now = clock.now.plus(Duration.ofDays(1))
        assertTrue(catalog.due())
        fixture.route("/api/logos.json") { Canned("down", 500, "text/plain") }
        catalog.refresh()
        assertFalse(catalog.due())
        clock.now = clock.now.plus(Duration.ofHours(6))
        assertTrue(catalog.due())
    }
}
