package dev.jamesrich.sofaking.backend.providers

import com.sun.net.httpserver.HttpExchange
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OgNetworkClientTest {
    private val nigbati = "460063"
    private val music = "460072"
    private val stories = "469075"
    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.000,\nseg100.ts\n#EXTINF:6.000,\nseg101.ts\n"

    private fun ProviderFixture.ogNetwork(token: () -> String = { "fixture-token" }) {
        fun guarded(body: () -> String) = { exchange: HttpExchange ->
            if (exchange.requestHeaders.getFirst("x-auth-token") != token()) {
                Canned("""{"error":"unauthorized"}""", 401)
            } else {
                Canned(body())
            }
        }
        json("/catalog.json", ProviderFixture.resource("ognetwork-catalog.json"))
        route("/sdp/v2/authorize") { Canned("""{"data":"${token()}"}""") }
        route("/sdp/v2/assets", guarded { ProviderFixture.resource("ognetwork-assets.json") })
        route("/sdp/v2/epg-events", guarded { ProviderFixture.resource("ognetwork-epg.json") })
        route("/sdp/v2/assets/$nigbati", guarded { ProviderFixture.resource("ognetwork-asset.json") })
        route("/sdp/v2/assets/$music", guarded { ProviderFixture.resource("ognetwork-asset-no-stream.json") })
        route("/sdp/v2/assets/$nigbati/resources/45013024/playback-sessions") { exchange ->
            if (exchange.requestMethod != "POST" || exchange.requestHeaders.getFirst("x-auth-token") != token()) {
                Canned("""{"error":"unauthorized"}""", 401)
            } else {
                Canned(ProviderFixture.resource("ognetwork-session.json").replace("BASE", base))
            }
        }
        text("/d/m.m3u8", ProviderFixture.MASTER.replace("media.m3u8", "v.m3u8"))
        text("/d/v.m3u8", media)
    }

    private fun options(fixture: ProviderFixture) = mapOf(
        "catalogUrl" to "${fixture.base}/catalog.json",
        "apiBase" to "${fixture.base}/sdp/v2",
    )

    @Test fun `lists the catalog's channels with their poster and first genre, and leaves out other asset types`() =
        providerTest("sofa-ognetwork-") {
            fixture.ogNetwork()
            val client = OgNetworkClient(context("ognetwork", options(fixture)))
            val channels = client.channels()
            assertEquals(listOf(nigbati, music, stories), channels.map { it.id })
            assertEquals("Nigbati TV", channels[0].name)
            assertEquals("Entertainment", channels[0].group)
            assertEquals(
                "https://origin002b-ams01.sotalcloud.com/contentserver/v2/distributions/45013025/content/3124991",
                channels[0].logo,
            )
            assertEquals("Music", channels[1].group)
            assertNull(channels[2].logo, "an empty poster is no logo")
            client.channels()
            assertEquals(1, fixture.hits("/catalog.json"), "the listing is cached")
            assertEquals(0, fixture.hits("/sdp/v2/authorize"), "the catalog needs no token")
        }

    @Test fun `one token serves the guide and every resolve, and it is sent the way the web app sends it`() =
        providerTest("sofa-ognetwork-") {
            fixture.ogNetwork()
            val client = OgNetworkClient(context("ognetwork", options(fixture)))
            client.guide(setOf(nigbati))
            client.resolve(nigbati)
            client.resolve(nigbati)
            assertEquals(1, fixture.hits("/sdp/v2/authorize"))
            val authorize = fixture.lastQuery["/sdp/v2/authorize"]!!
            assertTrue(authorize.startsWith("client_id=cloud%3Aognetwork_ottweb_device&grant_type=client_credentials"))
            assertEquals("fixture-token", fixture.lastHeaders["/sdp/v2/assets"]!!["x-auth-token"])
            assertEquals("https://play.theognetwork.net", fixture.lastHeaders["/sdp/v2/assets"]!!["origin"])
        }

    @Test fun `a refused token is replaced once and the request goes through`() = providerTest("sofa-ognetwork-") {
        var current = "token-one"
        fixture.ogNetwork { current }
        val client = OgNetworkClient(context("ognetwork", options(fixture)))
        client.resolve(nigbati)
        current = "token-two"
        val resolved = client.resolve(nigbati)
        assertEquals(2, fixture.hits("/sdp/v2/authorize"))
        assertEquals("token-two", fixture.lastHeaders["/sdp/v2/assets/$nigbati"]!!["x-auth-token"])
        assertTrue(resolved.url.contains("session_ticket="))
    }

    @Test fun `resolve opens a playback session and returns its URL, the web headers and a ticket-length expiry`() =
        providerTest("sofa-ognetwork-") {
            fixture.ogNetwork()
            val client = OgNetworkClient(context("ognetwork", options(fixture)))
            val resolved = client.resolve(nigbati)
            assertEquals(
                "${fixture.base}/d/m.m3u8?ads.channel_name=Nigbati+TV&session_ticket=fixture-ticket",
                resolved.url,
            )
            assertEquals("https://play.theognetwork.net", resolved.headers["Origin"])
            assertEquals("https://play.theognetwork.net/", resolved.headers["Referer"])
            assertEquals(clock.now.plus(Duration.ofMinutes(9)), resolved.expiresAt)
            assertEquals("{}", fixture.lastBody["/sdp/v2/assets/$nigbati/resources/45013024/playback-sessions"])
        }

    @Test fun `guide maps the wanted channel's airings, drops finished ones and settles overlaps and double syncs`() =
        providerTest("sofa-ognetwork-") {
            fixture.ogNetwork()
            val client = OgNetworkClient(context("ognetwork", options(fixture)))
            assertTrue(client.guide(emptySet()).programs.isEmpty())
            assertEquals(0, fixture.hits("/sdp/v2/epg-events"), "no wanted channel asks for no guide")
            val guide = client.guide(setOf(nigbati))
            assertEquals(3, guide.channels.size)
            assertEquals(
                listOf("Ajewole 2", "Oko Mi", "Late Show", "Fresh Sync"),
                guide.programs.map { it.title },
                "Delivery Boy has ended, the newer sync wins and the other channels were not asked for",
            )
            assertNull(guide.programs[0].description, "a lone Movie is no description")
            assertEquals("A farmer takes on the city.", guide.programs[1].description)
            assertEquals(
                "2026-10-10T16:00:00Z",
                guide.programs[1].endsAt.toString(),
                "a program ends where the next begins",
            )
            assertEquals("Talk and music.", guide.programs[2].description)
            assertTrue(guide.programs.all { it.channelId == nigbati })
        }

    @Test fun `an empty playlist or a missing live stream is a gone offering, not a service fault`() =
        providerTest("sofa-ognetwork-") {
            fixture.ogNetwork()
            val client = OgNetworkClient(context("ognetwork", options(fixture)))
            fixture.text("/d/v.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n")
            val empty = assertFailsWith<ProviderFailure> { client.resolve(nigbati) }
            assertFalse(empty.transient)
            assertFalse(empty.message!!.contains("fixture-ticket") || empty.message!!.contains("fixture-token"))
            assertFalse(assertFailsWith<ProviderFailure> { client.resolve(music) }.transient)
        }

    @Test fun `a failing service is a transient failure and a channel it no longer lists is not`() =
        providerTest("sofa-ognetwork-") {
            fixture.ogNetwork()
            val client = OgNetworkClient(context("ognetwork", options(fixture)))
            assertFailsWith<ProviderFailure> { client.resolve("999") }.let {
                assertFalse(it.transient)
            }
            fixture.route("/sdp/v2/assets/$nigbati") { Canned("gone", 404, "text/plain") }
            assertFailsWith<ProviderFailure> { client.resolve(nigbati) }.let { assertFalse(it.transient) }
            fixture.route("/sdp/v2/authorize") { Canned("down", 503, "text/plain") }
            fixture.route("/catalog.json") { Canned("down", 503, "text/plain") }
            val broken = OgNetworkClient(context("ognetwork-broken", options(fixture)))
            val failure = assertFailsWith<ProviderFailure> { broken.channels() }
            assertTrue(failure.transient)
            assertFalse(failure.message!!.contains(fixture.base), "the message carries no URL")
        }
}
