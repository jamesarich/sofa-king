package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.providerState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamsungClientTest {
    @Test fun `the gzip channel list is read by region without DRM channels and the redirect target is filled`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-samsung-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            // The mjh shape: a hop to the raw file, then gzip served as octet-stream.
                            fixture.route("/SamsungTVPlus/.channels.json.gz") {
                                Canned(status = 302, location = "${fixture.base}/raw/.channels.json.gz")
                            }
                            fixture.route("/raw/.channels.json.gz") {
                                Canned(
                                    200,
                                    ProviderFixture.resourceBytes("samsung-channels.json.gz"),
                                    "application/octet-stream",
                                )
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
                            val clock = MutableClock()
                            val options = mapOf(
                                "channelsUrl" to "${fixture.base}/SamsungTVPlus/.channels.json.gz",
                                "streamBase" to "${fixture.base}/stvp-",
                            )
                            val client = SamsungClient(ProviderFixture.context("samsung", options, http, store, clock))
                            val channels = client.channels()
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
                            assertEquals("okhttp/4.12.0", fixture.lastHeaders["/raw/.channels.json.gz"]!!["user-agent"])
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
                            assertFailsWith<ProviderFailure> {
                                client.resolve("USBA3000041ZP")
                            }.let { assertFalse(it.transient) }
                            assertEquals(
                                1,
                                fixture.hits("/raw/.channels.json.gz") - 1,
                                "an unknown id refreshes the list once",
                            )
                            assertFailsWith<ProviderFailure> {
                                SamsungClient(
                                    ProviderFixture.context("s2", options + ("region" to "fr"), http, store, clock),
                                ).channels()
                            }
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
