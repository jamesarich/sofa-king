package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveProviderState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalNowClientTest {
    private val desMoines = "22A7F880-7B2C-4658-B1C4-33F996DA38A9"
    private val pureFlix = "8CA12BB0-5B8F-4696-9B09-42C1D0D4F366"

    private fun ProviderFixture.localNow() {
        text("/", ProviderFixture.resource("localnow-page.html"), "text/html")
        fun guarded(answer: () -> Canned) = { exchange: com.sun.net.httpserver.HttpExchange ->
            if (exchange.requestHeaders.getFirst("x-access-token") != "fixture-token") {
                Canned("""{"success":false}""", 401)
            } else {
                answer()
            }
        }
        route("/dsp/live/epg/US/website", guarded { Canned(ProviderFixture.resource("localnow-epg.json")) })
        route(
            "/dsp/video/play/$desMoines/1920/1080",
            guarded {
                Canned(ProviderFixture.resource("localnow-play.json").replace("BASE", base))
            },
        )
        route(
            "/dsp/video/play/$pureFlix/1920/1080",
            guarded {
                Canned(
                    """{"_id":"$pureFlix","drm_config":{"widevine":"fixture"},"video_m3u8":"$base/ln/locked.m3u8"}""",
                )
            },
        )
    }

    private fun options(fixture: ProviderFixture, extra: Map<String, String> = emptyMap()) =
        mapOf("pageUrl" to "${fixture.base}/", "apiBase" to "${fixture.base}/dsp") + extra

    @Test fun `lists unlocked channels once for the configured market with their programmes`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-localnow-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.localNow()
                        val client = LocalNowClient(
                            ProviderFixture.context(
                                "localnow",
                                options(
                                    fixture,
                                    mapOf(
                                        "dma" to "679",
                                        "market" to "iaDesMoines",
                                    ),
                                ),
                                http,
                                store,
                                MutableClock(),
                            ),
                        )
                        val channels = client.channels()
                        assertEquals(
                            listOf(desMoines, pureFlix),
                            channels.map {
                                it.id
                            },
                            "the locked channel is left out, the repeat listed once",
                        )
                        assertEquals("Local Now Des Moines", channels[0].name)
                        assertEquals("3057", channels[0].number)
                        assertEquals("My City", channels[0].group)
                        assertTrue(
                            channels[0].logo!!.startsWith("https://image-resizer-cloud-cdn.api.cms.amdvids.com/"),
                        )
                        assertEquals("dma=679&market=iaDesMoines", fixture.lastQuery["/dsp/live/epg/US/website"])
                        assertEquals(1, fixture.hits("/"))
                        val guide = client.guide(setOf(desMoines))
                        assertEquals(2, guide.channels.size)
                        assertEquals(
                            listOf("Local Now Tonight", "Your News & Weather"),
                            guide.programmes.map {
                                it.title
                            },
                        )
                        assertEquals(1, fixture.hits("/dsp/live/epg/US/website"), "the guide reuses the listing")
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a refused token rereads the page and resolve takes the stitched master unless it needs DRM`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-localnow-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.localNow()
                            val clock = MutableClock()
                            store.saveProviderState(
                                "localnow",
                                "runtime",
                                "${fixture.base}/dsp\nstale-token",
                                clock.now.plusSeconds(600),
                                clock.now,
                            )
                            val client =
                                LocalNowClient(
                                    ProviderFixture.context("localnow", options(fixture), http, store, clock),
                                )
                            val resolved = client.resolve(desMoines)
                            assertEquals(1, fixture.hits("/"))
                            assertNull(fixture.lastQuery["/dsp/live/epg/US/website"], "no market configured, none sent")
                            assertEquals("${fixture.base}/ln/playlist.m3u8?ads.playbackid=$desMoines", resolved.url)
                            assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
                            assertTrue(
                                fixture.lastQuery["/dsp/video/play/$desMoines/1920/1080"]!!.startsWith(
                                    "page_url=https%3A%2F%2Flocalnow.com%2Fchannels%2Fepg-local-now-des-moines&",
                                ),
                            )
                            assertFailsWith<ProviderFailure> {
                                client.resolve(pureFlix)
                            }.let { assertFalse(it.transient, "a DRM config is the channel's fault") }
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
