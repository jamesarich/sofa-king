package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.providerTest
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvOrgClientTest {
    private suspend fun load(
        scope: dev.jamesrich.sofaking.backend.providers.ProviderScope,
        settings: IptvOrgSettings,
        zone: ZoneId = ZoneId.of("America/New_York"),
    ): IptvOrgClient = with(scope) {
        serveIptvOrg()
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { settings }, zone)
        catalog.refresh()
        IptvOrgClient(catalog) { settings }
    }

    @Test fun `lists the streams of the region and leaves out blocklisted closed and nsfw channels`() =
        providerTest("iptv-org-") {
            val client = load(this, IptvOrgSettings(streams = true, region = "US"))
            val channels = client.channels()
            assertEquals(listOf("CNN", "CNN", "News 24"), channels.map { it.name })
            val first = channels.first()
            assertEquals("News", first.group)
            assertEquals("https://logos.example/cnn.png", first.logo)
            assertEquals(listOf("us", "720p"), first.tags)
            assertEquals(listOf("us", "1080p"), channels[1].tags)
        }

    @Test fun `an explicit region replaces the one from the time zone`() = providerTest("iptv-org-") {
        val client = load(this, IptvOrgSettings(streams = true, region = "GB"))
        assertEquals(listOf("BBC One"), client.channels().map { it.name })
    }

    @Test fun `without a region the server's time zone picks the country`() = providerTest("iptv-org-") {
        val london = load(this, IptvOrgSettings(streams = true), ZoneId.of("Europe/London"))
        assertEquals(listOf("BBC One"), london.channels().map { it.name })
        val newYork = load(this, IptvOrgSettings(streams = true), ZoneId.of("America/New_York"))
        assertEquals(3, newYork.channels().size)
    }

    @Test fun `resolve carries the referrer and user agent of the stream`() = providerTest("iptv-org-") {
        val client = load(this, IptvOrgSettings(streams = true, region = "US"))
        val withHeaders = client.channels().first()
        val resolved = client.resolve(withHeaders.id)
        assertEquals("https://streams.example/cnn/index.m3u8", resolved.url)
        assertEquals("https://www.cnn.com/", resolved.headers["Referer"])
        assertEquals("Mozilla/5.0 Fixture", resolved.headers["User-Agent"])
        val plain = client.resolve(client.channels()[1].id)
        assertTrue(plain.headers.isEmpty())
        assertNull(plain.expiresAt)
        assertFailsWith<ProviderFailure> { client.resolve("CNN.us#00000000") }
    }

    @Test fun `lists nothing while streams are off even with the files on disk`() = providerTest("iptv-org-") {
        val settings = IptvOrgSettings(streams = true, region = "US")
        val on = load(this, settings)
        assertTrue(on.channels().isNotEmpty())
        var current = settings
        val catalog = iptvOrgCatalog(Files.createTempDirectory("iptv-org-data"), { current })
        catalog.refresh()
        val client = IptvOrgClient(catalog) { current }
        current = IptvOrgSettings(metadata = true, region = "US")
        assertTrue(client.channels().isEmpty())
        assertFailsWith<ProviderFailure> { client.resolve("CNN.us#00000000") }
    }
}
