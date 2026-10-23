package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.providerState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreeLiveSportsClientTest {
    @Test fun `one catalog document yields channels in number order, the embedded schedule and fresh macros`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-fls-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.json(
                                "/catalog/key",
                                ProviderFixture.resource("freelivesports-catalog.json").replace("BASE", fixture.base),
                            )
                            val clock = MutableClock()
                            val client =
                                FreeLiveSportsClient(
                                    ProviderFixture.context(
                                        "fls",
                                        mapOf(
                                            "catalogUrl" to
                                                "${fixture.base}/catalog/key?__site=freelivesports&__source=web",
                                        ),
                                        http,
                                        store,
                                        clock,
                                    ),
                                )
                            val channels = client.channels()
                            assertEquals(
                                listOf("Overtime", "MSG Sports Zone"),
                                channels.map {
                                    it.name
                                },
                                "sorted by channel number, not document order",
                            )
                            assertEquals(listOf("1", "2"), channels.map { it.number })
                            assertEquals("Sports", channels.first().group)
                            assertEquals(
                                "https://media.unreel.me/prod/freelivesports/logo/e23d456b-edc1-44c6-a4e0-b90fe7bf967e",
                                channels.first().logo,
                            )
                            assertEquals("__site=freelivesports&__source=web", fixture.lastQuery["/catalog/key"])
                            assertEquals(
                                "https://www.freelivesports.tv",
                                fixture.lastHeaders["/catalog/key"]!!["origin"],
                            )
                            val guide = client.guide(setOf("6902789463cbad62d1ce4a43"))
                            assertEquals(2, guide.programmes.size)
                            assertEquals("UMass-Lowell at Binghamton", guide.programmes.first().title)
                            assertEquals(
                                "From Bearcats Sports Complex in Binghamton, N.Y.",
                                guide.programmes.first().description,
                            )
                            assertTrue(client.guide(setOf("69658066975ee235f015da2b")).programmes.isEmpty())
                            val resolved = client.resolve("6902789463cbad62d1ce4a43")
                            val deviceId = store.providerState("fls", ProviderContext.DEVICE_ID)!!.value
                            assertEquals(
                                "${fixture.base}/fls/master.m3u8?ads.us_privacy=&ads.device_id=$deviceId&ads.cb=${clock.now.epochSecond}&ads.ref=https%3A%2F%2Fwww.freelivesports.tv%2F",
                                resolved.url,
                            )
                            clock.now = clock.now.plusSeconds(5)
                            assertTrue(
                                client.resolve(
                                    "6902789463cbad62d1ce4a43",
                                ).url.contains("ads.cb=${clock.now.epochSecond}"),
                                "the cache buster is fresh per resolve",
                            )
                            assertEquals(1, fixture.hits("/catalog/key"))
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
