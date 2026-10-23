package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PbsClientTest {
    private val station = "4b65e70a-e262-4290-ae67-45bd0814cafd"

    @Test fun `a zip code lists only the clear feeds of its stations and the guide follows the station's day`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-pbs-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.json("/localize/zipcode/50309/", ProviderFixture.resource("pbs-localize.json"))
            fixture.json("/api/station/$station/stations-list/", ProviderFixture.resource("pbs-station.json").replace("BASE", fixture.base))
            fixture.json("/api/livestream-schedule/$station/", ProviderFixture.resource("pbs-schedule.json"))
            val clock = MutableClock()
            val options = mapOf("zip" to "50309", "localizeBase" to fixture.base, "siteBase" to fixture.base, "playerBase" to fixture.base)
            val client = PbsClient(ProviderFixture.context("pbs", options, http, store, clock))
            val channels = client.channels()
            assertEquals(listOf("$station:ga-create", "$station:ga-world"), channels.map { it.id }, "the DRM main channel and the empty FNX feed are left out")
            assertEquals("PBS Create (KDIN)", channels[0].name); assertEquals("PBS", channels[0].group)
            assertEquals("https://image.pbs.org/stations/kdin-color-logo-oPnCcjN.png", channels[0].logo)
            assertEquals("${fixture.base}/pbs/ga-create/redirect/", client.resolve("$station:ga-create").url)
            val guide = client.guide(setOf("$station:ga-create"))
            assertEquals(2, guide.channels.size)
            assertEquals(2, fixture.hits("/api/livestream-schedule/$station/"), "today and tomorrow")
            assertEquals("date=2026-10-10&timezone=America/Chicago", fixture.lastQuery["/api/livestream-schedule/$station/"]!!.replace("date=2026-10-11", "date=2026-10-10"))
            assertEquals(listOf("A Craftsman's Legacy"), guide.programmes.map { it.title }.distinct(), "the main channel's listings are not wanted")
            assertEquals(2, guide.programmes.size, "the same fixture day twice collapses to one set")
            assertEquals("2026-10-10T05:30:00Z", guide.programmes[0].endsAt.toString())
            assertEquals("The Shoe Makers", guide.programmes[0].subTitle, "the episode title is the sub-title")
            assertEquals("Making shoes.", guide.programmes[0].description)
            assertEquals("The Furniture Maker", guide.programmes[1].subTitle)
            assertEquals(1, fixture.hits("/localize/zipcode/50309/"))
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `without a zip or stations the import fails plainly`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-pbs-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            val client = PbsClient(ProviderFixture.context("pbs", mapOf("siteBase" to fixture.base), http, store, MutableClock()))
            assertFailsWith<ProviderFailure> { client.channels() }.let { assertFalse(it.transient) }
            fixture.json("/api/station/$station/stations-list/", ProviderFixture.resource("pbs-station.json").replace("BASE", fixture.base))
            val explicit = PbsClient(ProviderFixture.context("pbs2", mapOf("stations" to station, "siteBase" to fixture.base), http, store, MutableClock()))
            assertEquals(2, explicit.channels().size)
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a station that fails for now fails the import, and one PBS no longer lists is skipped`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-pbs-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.json("/api/station/$station/stations-list/", ProviderFixture.resource("pbs-station.json").replace("BASE", fixture.base))
            fixture.route("/api/station/busy/stations-list/") { Canned("{}", 503) }
            fixture.route("/api/station/gone/stations-list/") { Canned("{}", 404) }
            val busy = PbsClient(ProviderFixture.context("pbs", mapOf("stations" to "$station,busy", "siteBase" to fixture.base), http, store, MutableClock()))
            assertTrue(assertFailsWith<ProviderFailure> { busy.channels() }.transient, "the last import's channels stay")
            val gone = PbsClient(ProviderFixture.context("pbs2", mapOf("stations" to "$station,gone", "siteBase" to fixture.base), http, store, MutableClock()))
            assertEquals(2, gone.channels().size)
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}
