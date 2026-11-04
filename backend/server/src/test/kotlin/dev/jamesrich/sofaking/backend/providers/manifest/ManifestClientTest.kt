package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.providers.Canned
import dev.jamesrich.sofaking.backend.providers.ProviderChannel
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ProviderFixture
import dev.jamesrich.sofaking.backend.providers.ProviderScope
import dev.jamesrich.sofaking.backend.providers.providerTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestClientTest {
    private val feedPath = "/feed/page_1/pagesize_75/device_web/apiversion_24/cacheversion_" +
        "${java.time.Instant.parse("2026-10-10T13:00:00Z").epochSecond / 900}"

    private fun cwChannel(id: String, name: String, group: String, thumbnail: String = id) = ProviderChannel(
        id,
        name,
        logo = "https://static.cwtv.com/images/masters/cw/universal/channels/" + thumbnail + "/channel-thumbnail.jpg",
        group = group,
        tags = listOf("us"),
    )

    private fun hallmarkChannel(id: String, name: String, number: String, item: String?, hash: String?) =
        ProviderChannel(
            id,
            name,
            number,
            item?.let {
                "https://097d0e9f.customer.static.core.one.accedo.tv/097d0e9f2de0f04b/image/item/" + it +
                    "/logo/landscape/channelimage?locale=en&one_etagHash=" + hash
            },
            "Movies",
            listOf("us"),
        )

    private val hallmarkListed = listOf(
        hallmarkChannel(
            "hallmark_hits",
            "Non-Stop Hallmark Hits",
            "9001",
            "Linear-1543-Non-Stop-Hallmark-Hits-DRM",
            "105a41f6",
        ),
        hallmarkChannel(
            "hallmark_romcoms",
            "Non-Stop Rom-Coms",
            "9002",
            "Linear-1268-Non-Stop-RomComs-DRM",
            "9f6b665b",
        ),
        hallmarkChannel(
            "hallmark_christmas",
            "Non-Stop Christmas",
            "9003",
            "Linear-1187-Non-Stop-Christmas-DRM",
            "eced6ce0",
        ),
        hallmarkChannel(
            "hallmark_mysteries",
            "Non-Stop Mysteries",
            "9004",
            "Linear-1186-Non-Stop-Mysteries-DRM",
            "016dea50",
        ),
    )

    private val hallmarkUnlisted = listOf(
        hallmarkChannel(
            "hallmark_hits",
            "Non-Stop Hallmark Hits",
            "9001",
            null,
            null,
        ),
        hallmarkChannel(
            "hallmark_romcoms",
            "Non-Stop Rom-Coms",
            "9002",
            null,
            null,
        ),
        hallmarkChannel(
            "hallmark_christmas",
            "Non-Stop Christmas",
            "9003",
            null,
            null,
        ),
        hallmarkChannel(
            "hallmark_mysteries",
            "Non-Stop Mysteries",
            "9004",
            null,
            null,
        ),
    )

    private fun manifests() = ProviderManifests(Files.createTempDirectory("sofa-manifests-").resolve("none"))

    private fun ProviderScope.cw() =
        ManifestClient(context("cw", mapOf("feedBase" to "${fixture.base}/feed")), manifests(), "cw")

    private fun ProviderScope.hallmark(options: Map<String, String>) =
        ManifestClient(context("hallmark", options), manifests(), "hallmark")

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
        assertEquals(
            listOf(
                cwChannel("cw-forever", "CW Forever", "Entertainment"),
                cwChannel("ion", "ION", "Drama"),
                cwChannel("court-tv", "Court TV", "Crime"),
                cwChannel("filmrise-drama", "FilmRise Drama", "Drama"),
                cwChannel("tba-sample", "TBA Sample", "Drama", "ion"),
            ),
            channels,
        )
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
        val guide = cw().guide(setOf("cw-forever", "tba-sample"))!!
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
        assertTrue(cw().guide(emptySet())!!.programs.isEmpty())
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

    @Test fun `four fixed channels with static streams, catalog logos and a schedule per wanted channel`() =
        providerTest("sofa-hallmark-") {
            fixture.json("/catalog/channels", ProviderFixture.resource("hallmark-catalog.json"))
            fixture.json(
                "/catalog/channels/Linear-1187-Non-Stop-Christmas-DRM/schedule",
                ProviderFixture.resource("hallmark-schedule.json"),
            )
            val options = mapOf(
                "apiBase" to "${fixture.base}/catalog",
                "streamBase" to "${fixture.base}/hallmark/%s/playlist.m3u8",
            )
            val client = hallmark(options)
            val channels = client.channels()
            assertEquals(
                listOf("hallmark_hits", "hallmark_romcoms", "hallmark_christmas", "hallmark_mysteries"),
                channels.map { it.id },
            )
            assertEquals("9003", channels[2].number)
            assertEquals("Movies", channels[2].group)
            assertTrue(
                channels[2].logo!!.contains("/Linear-1187-Non-Stop-Christmas-DRM/logo/"),
                "the logo comes from the catalog by channel identifier",
            )
            assertEquals(hallmarkListed, channels)
            assertEquals(
                "${fixture.base}/hallmark/christmas/playlist.m3u8",
                client.resolve("hallmark_christmas").url,
            )
            assertEquals(mapOf("User-Agent" to "Mozilla/5.0"), client.resolve("hallmark_hits").headers)
            assertNull(client.resolve("hallmark_hits").expiresAt, "static URLs do not expire")
            val guide = client.guide(setOf("hallmark_christmas"))!!
            assertEquals(4, guide.channels.size)
            val schedule = "/catalog/channels/Linear-1187-Non-Stop-Christmas-DRM/schedule"
            assertEquals(1, fixture.hits(schedule))
            assertTrue(
                fixture.lastQuery[schedule]!!.startsWith(
                    "from=2026-10-10T12:00:00.000Z&until=2026-10-12T12:00:00.000Z&locale=en",
                ),
            )
            assertEquals(
                listOf("Merry Christmas, Ted Cooper!", "An Unexpected Christmas"),
                guide.programs.map { it.title },
            )
            assertTrue(guide.programs.all { it.channelId == "hallmark_christmas" })
            assertEquals(1, fixture.hits("/catalog/channels"), "the catalog is shared by the listing and the guide")
            assertFailsWith<ProviderFailure> { client.resolve("hallmark_western") }.let { assertFalse(it.transient) }
        }

    @Test fun `an unreachable catalog still lists the channels without logos`() = providerTest("sofa-hallmark-") {
        val client = hallmark(mapOf("apiBase" to "${fixture.base}/catalog"))
        val channels = client.channels()
        assertEquals(4, channels.size)
        assertTrue(channels.all { it.logo == null })
        assertEquals(hallmarkUnlisted, channels)
        assertFailsWith<ProviderFailure> { client.guide(setOf("hallmark_hits")) }
    }
}
