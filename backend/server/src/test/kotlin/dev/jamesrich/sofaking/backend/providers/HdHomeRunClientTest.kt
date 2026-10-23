package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HdHomeRunClientTest {
    private fun ProviderFixture.tuner() {
        json(
            "/discover.json",
            """{"FriendlyName":"HDHomeRun FLEX """ +
                """4K","ModelNumber":"HDFX-4K","DeviceID":"10AFFFFF","DeviceAuth":"abc/""" +
                """def+","BaseURL":"$base","LineupURL":"$base/lineup.json","TunerCount":4}""",
        )
        json(
            "/lineup.json",
            """[
              {"GuideNumber":"5.1","GuideName":"WOI-DT","URL":"$base:5004/auto/v5.1","HD":1},
              {"GuideNumber":"5.2","GuideName":"MeTV","URL":"$base:5004/auto/v5.2"},
              {"GuideNumber":"8.1","GuideName":"KCCI","URL":"$base:5004/auto/v8.1","DRM":1},
              {"GuideNumber":"13.3","GuideName":"Locked","URL":"$base:5004/auto/v13.3","Tags":"favorite,drm"},
              {"GuideNumber":"5.1","GuideName":"WOI-DT again","URL":"$base:5004/auto/v5.1"}
            ]""",
        )
        route("/api/xmltv") { exchange ->
            if (exchange.requestURI.rawQuery != "DeviceAuth=abc%2Fdef%2B") {
                Canned("""{"error":"auth"}""", 401)
            } else {
                Canned(
                    """<?xml version="1.0" encoding="UTF-8"?>
                <tv>
                  <channel id="10AFFFFF-5.1"><display-name>5.1</display-name><display-name>WOI-DT</display-name></channel>
                  <channel id="10AFFFFF-5.2"><display-name>MeTV</display-name></channel>
                  <channel id="10AFFFFF-8.1"><display-name>8.1</display-name></channel>
                  <programme start="20261010120000 +0000" stop="20261010123000 +0000" channel="10AFFFFF-5.1"><title>Ended</title></programme>
                  <programme start="20261010123000 +0000" stop="20261010133000 +0000" channel="10AFFFFF-5.1"><title>Local News</title><desc>Noon news.</desc></programme>
                  <programme start="20261010130000 +0000" stop="20261010140000 +0000" channel="10AFFFFF-5.2"><title>Perry Mason</title></programme>
                  <programme start="20261010130000 +0000" stop="20261010140000 +0000" channel="10AFFFFF-8.1"><title>DRM program</title></programme>
                </tv>
                    """.trimIndent(),
                    contentType = "application/xml",
                )
            }
        }
    }

    private fun options(fixture: ProviderFixture, extra: Map<String, String> = emptyMap()) =
        mapOf("url" to "${fixture.base}/", "guideApi" to "${fixture.base}/api/xmltv") + extra

    @Test fun `lists each clear channel once by its guide number as a local MPEG-TS source`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hdhr-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.tuner()
                        val client =
                            HdHomeRunClient(
                                ProviderFixture.context("ota", options(fixture), http, store, MutableClock()),
                            )
                        val channels = client.channels()
                        assertEquals(
                            listOf("5.1", "5.2"),
                            channels.map {
                                it.id
                            },
                            "DRM channels, by flag or tag, are left out and a repeated number is listed once",
                        )
                        val woi = channels[0]
                        assertEquals("WOI-DT", woi.name)
                        assertEquals("5.1", woi.number)
                        assertEquals("Local", woi.group)
                        assertEquals(listOf("ota"), woi.tags)
                        assertEquals("mpegts", client.protocol)
                        val registry = ProviderRegistry(mapOf("ota" to client), MutableClock())
                        val parsed = registry.channels(
                            FeedConfig("ota", type = "native", service = "hdhomerun", options = options(fixture)),
                        )
                        assertEquals(
                            setOf("mpegts"),
                            parsed.offerings.map {
                                it.protocol
                            }.toSet(),
                            "the registry carries the client's protocol",
                        )
                        assertEquals("svc://hdhomerun/5.1", parsed.offerings[0].url)
                        assertEquals(
                            1,
                            fixture.hits("/lineup.json"),
                            "the lineup is read once for the list and the registry",
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `resolve answers the device's own stream URL, with the transcode profile when configured`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-hdhr-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.tuner()
                            val plain =
                                HdHomeRunClient(
                                    ProviderFixture.context("ota", options(fixture), http, store, MutableClock()),
                                )
                            val resolved = plain.resolve("5.2")
                            assertEquals("${fixture.base}:5004/auto/v5.2", resolved.url)
                            assertTrue(resolved.headers.isEmpty())
                            assertNull(resolved.expiresAt, "a tuner URL does not expire")
                            assertFailsWith<ProviderFailure> {
                                plain.resolve("8.1")
                            }.let { assertFalse(it.transient, "a DRM or unknown channel is gone, not a tuner fault") }
                            assertEquals(
                                2,
                                fixture.hits("/lineup.json"),
                                "an unknown channel refreshes the lineup once",
                            )
                            val extend =
                                HdHomeRunClient(
                                    ProviderFixture.context(
                                        "ota2",
                                        options(fixture, mapOf("transcode" to "mobile")),
                                        http,
                                        store,
                                        MutableClock(),
                                    ),
                                )
                            assertEquals("${fixture.base}:5004/auto/v5.1?transcode=mobile", extend.resolve("5.1").url)
                            assertFailsWith<IllegalArgumentException> {
                                HdHomeRunClient(
                                    ProviderFixture.context(
                                        "ota3",
                                        mapOf("guideApi" to "x"),
                                        http,
                                        store,
                                        MutableClock(),
                                    ),
                                )
                            }
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `guide fetches the device's XMLTV with its DeviceAuth and maps it onto the lineup numbers`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-hdhr-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.tuner()
                            val clock = MutableClock(Instant.parse("2026-10-10T13:00:00Z"))
                            val client =
                                HdHomeRunClient(ProviderFixture.context("ota", options(fixture), http, store, clock))
                            val index = client.guide(emptySet())
                            assertEquals(
                                listOf("5.1", "5.2"),
                                index.channels.map {
                                    it.id
                                },
                                "the index is the lineup, not the XMLTV's ids",
                            )
                            assertTrue(index.programs.isEmpty())
                            val guide = client.guide(setOf("5.1", "8.1"))
                            assertEquals(
                                listOf("Local News"),
                                guide.programs.map {
                                    it.title
                                },
                                "ended programs, unmapped channels and DRM channels are dropped",
                            )
                            assertEquals("5.1", guide.programs[0].channelId)
                            assertEquals("Noon news.", guide.programs[0].description)
                            assertEquals(
                                listOf("Perry Mason"),
                                client.guide(setOf("5.2")).programs.map {
                                    it.title
                                },
                                "a channel matched by display name maps too",
                            )
                            assertEquals(
                                1,
                                fixture.hits("/api/xmltv"),
                                "the XMLTV is fetched once a day, whatever the refresh",
                            )
                            assertEquals(1, fixture.hits("/discover.json"))
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
