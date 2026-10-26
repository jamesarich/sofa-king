package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.store.providerState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RAW_LIST = "/raw/.channels.json.gz"
private const val GITHUB_LIST = "/github/SamsungTVPlus/.channels.json.gz"

class SamsungClientTest {
    /** The options pointing the client at the fixture. */
    private val ProviderScope.options: Map<String, String>
        get() = mapOf(
            "channelsUrl" to "${fixture.base}/SamsungTVPlus/.channels.json.gz",
            "streamBase" to "${fixture.base}/stvp-",
            "fallbackUrl" to "${fixture.base}$GITHUB_LIST",
        )

    /** The client over the fixture's channel list and redirector, in the mjh shape. */
    private fun ProviderScope.samsung(): SamsungClient {
        // A hop to the raw file, then gzip served as octet-stream.
        fixture.route("/SamsungTVPlus/.channels.json.gz") {
            Canned(status = 302, location = "${fixture.base}/raw/.channels.json.gz")
        }
        fixture.route(RAW_LIST) {
            Canned(200, ProviderFixture.resourceBytes("samsung-channels.json.gz"), "application/octet-stream")
        }
        fixture.route("/stvp-USBA370000104") {
            Canned(
                status = 302,
                location = "${fixture.base}/sis/sc-xv9.m3u8?ads.device_did=%7BPSID%7D&" +
                    "ads.device_dnt=%7BTARGETOPT%7D&ads.us_privacy=%7BUS_PRIVACY%7D&" +
                    "ads.app_name=%7BAPP_NAME%7D&ads.service_id=USBA370000104",
            )
        }
        fixture.route("/stvp-USBC39000171G") { Canned(status = 404) }
        return SamsungClient(context("samsung", options))
    }

    @Test fun `the gzip channel list is read by region without DRM channels`() = providerTest("sofa-samsung-") {
        val channels = samsung().channels()
        assertEquals(
            listOf("USBA370000104", "USBC39000171G"),
            channels.map {
                it.id
            },
            "the channel with a license_url is DRM",
        )
        assertEquals("CBS News 24/7", channels[0].name)
        assertEquals("1110", channels[0].number)
        assertEquals("News & Opinion", channels[0].group)
        assertEquals(listOf("us"), channels[0].tags)
        assertTrue(channels[0].logo!!.startsWith("https://tvpnlogopus.samsungcloud.tv/"))
        assertEquals("okhttp/4.12.0", fixture.lastHeaders[RAW_LIST]!!["user-agent"])
        assertFailsWith<ProviderFailure> {
            SamsungClient(context("s2", options + ("region" to "fr"))).channels()
        }
    }

    @Test fun `resolve fills the redirect target, and an unknown id refreshes the list once`() =
        providerTest("sofa-samsung-") {
            val client = samsung()
            client.channels()
            val resolved = client.resolve("USBA370000104")
            val deviceId = store.providerState("samsung", ProviderContext.DEVICE_ID)!!.value
            assertEquals(
                "${fixture.base}/sis/sc-xv9.m3u8?ads.device_did=$deviceId&ads.device_dnt=0&" +
                    "ads.us_privacy=1---&ads.app_name=&ads.service_id=USBA370000104",
                resolved.url,
            )
            assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
            assertFailsWith<ProviderFailure> {
                client.resolve("USBC39000171G")
            }.let { assertFalse(it.transient, "a 404 from the redirector means the channel is gone") }
            assertFailsWith<ProviderFailure> { client.resolve("USBA3000041ZP") }.let { assertFalse(it.transient) }
            assertEquals(1, fixture.hits(RAW_LIST) - 1, "an unknown id refreshes the list once")
        }

    @Test fun `a failing mirror falls back to its GitHub copy, then to the last list that loaded`() =
        providerTest("sofa-samsung-") {
            val client = samsung()
            fixture.route("/SamsungTVPlus/.channels.json.gz") { Canned("unavailable", 503, "text/plain") }
            fixture.route(GITHUB_LIST) {
                Canned(200, ProviderFixture.resourceBytes("samsung-channels.json.gz"), "application/octet-stream")
            }
            assertEquals(listOf("USBA370000104", "USBC39000171G"), client.channels().map { it.id })
            assertEquals(1, fixture.hits(GITHUB_LIST))

            fixture.route(GITHUB_LIST) { Canned("gone", 404, "text/plain") }
            clock.now = clock.now.plus(Duration.ofHours(1))
            val restarted = SamsungClient(context("samsung", options))
            val kept = restarted.channels()
            assertEquals(
                listOf("USBA370000104", "USBC39000171G"),
                kept.map {
                    it.id
                },
                "the last list survives a restart",
            )
            assertEquals("News & Opinion", kept[0].group)
            assertTrue(
                restarted.resolve("USBA370000104").url.endsWith("service_id=USBA370000104"),
                "a resolve finds the channel in the kept list",
            )
        }

    @Test fun `with no list ever loaded, a failing mirror and copy fail the listing`() = providerTest("sofa-samsung-") {
        samsung()
        fixture.route("/SamsungTVPlus/.channels.json.gz") { Canned("unavailable", 503, "text/plain") }
        assertFailsWith<ProviderFailure> { SamsungClient(context("samsung", options)).channels() }
    }
}
