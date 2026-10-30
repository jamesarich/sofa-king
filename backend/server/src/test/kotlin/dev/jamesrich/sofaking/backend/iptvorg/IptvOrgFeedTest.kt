package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.providers.Canned
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.providers.providerTest
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvOrgFeedTest {
    private val config = FeedConfig("iptv-org", type = "native", service = "iptv-org", allowEmpty = true)

    @Test fun `imported streams start unverified and keep the last list when the download later fails`() =
        providerTest("iptv-org-") {
            serveIptvOrg()
            val settings = IptvOrgSettings(streams = true, region = "US")
            val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { settings }, ZoneId.of("UTC"))
            catalog.refresh()
            val registry = ProviderRegistry(mapOf("iptv-org" to IptvOrgClient(catalog) { settings }), clock)
            val feeds = FeedService(listOf(config), store, http, clock, UpstreamPolicy(allowLoopback = true), registry)
            feeds.initialize()
            feeds.refresh(config)

            val offerings = feeds.published.offerings
            assertEquals(3, offerings.size)
            assertTrue(offerings.all { it.url.startsWith("svc://iptv-org/") }, "no stream URL is a source URL")
            for (offering in offerings) {
                assertEquals(HealthStatus.UNVERIFIED, store.health(offering.id)?.status, offering.id)
            }

            fixture.route("/api/streams.json") { Canned("down", 500, "text/plain") }
            val failed = catalog.refresh()
            assertFalse(failed.ok)
            feeds.refresh(config)
            assertEquals(3, feeds.published.offerings.size, "a failed download keeps the last copy in use")
        }

    @Test fun `a provider with streams off imports without channels and without a request`() = providerTest(
        "iptv-org-",
    ) {
        serveIptvOrg()
        val settings = IptvOrgSettings(metadata = true)
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { settings })
        val registry = ProviderRegistry(mapOf("iptv-org" to IptvOrgClient(catalog) { settings }), clock)
        val feeds = FeedService(listOf(config), store, http, clock, UpstreamPolicy(allowLoopback = true), registry)
        feeds.initialize()
        feeds.refresh(config)
        assertTrue(feeds.published.offerings.isEmpty())
        assertEquals(0, fixture.hits("/api/streams.json"))
    }
}
