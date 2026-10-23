package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HallmarkClientTest {
    @Test fun `four fixed channels with static streams, catalog logos and a schedule per wanted channel`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-hallmark-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.json("/catalog/channels", ProviderFixture.resource("hallmark-catalog.json"))
                            fixture.json(
                                "/catalog/channels/Linear-1187-Non-Stop-Christmas-DRM/schedule",
                                ProviderFixture.resource("hallmark-schedule.json"),
                            )
                            val clock = MutableClock()
                            val client =
                                HallmarkClient(
                                    ProviderFixture.context(
                                        "hallmark",
                                        mapOf(
                                            "apiBase" to "${fixture.base}/catalog",
                                            "streamBase" to "${fixture.base}/hallmark/%s/playlist.m3u8",
                                        ),
                                        http,
                                        store,
                                        clock,
                                    ),
                                )
                            val channels = client.channels()
                            assertEquals(
                                listOf("hallmark_hits", "hallmark_romcoms", "hallmark_christmas", "hallmark_mysteries"),
                                channels.map {
                                    it.id
                                },
                            )
                            assertEquals("9003", channels[2].number)
                            assertEquals("Movies", channels[2].group)
                            assertTrue(
                                channels[2].logo!!.contains("/Linear-1187-Non-Stop-Christmas-DRM/logo/"),
                                "the logo comes from the catalog by channel identifier",
                            )
                            assertEquals(
                                "${fixture.base}/hallmark/christmas/playlist.m3u8",
                                client.resolve("hallmark_christmas").url,
                            )
                            assertNull(client.resolve("hallmark_hits").expiresAt, "static URLs do not expire")
                            val guide = client.guide(setOf("hallmark_christmas"))
                            assertEquals(4, guide.channels.size)
                            assertEquals(
                                1,
                                fixture.hits("/catalog/channels/Linear-1187-Non-Stop-Christmas-DRM/schedule"),
                            )
                            assertTrue(
                                fixture.lastQuery[
                                    "/catalog/channels/Linear-1187-Non-Stop-Christmas-DRM/schedule",
                                ]!!.startsWith(
                                    "from=2026-10-10T12:00:00.000Z&until=2026-10-12T12:00:00.000Z&locale=en",
                                ),
                            )
                            assertEquals(
                                listOf("Merry Christmas, Ted Cooper!", "An Unexpected Christmas"),
                                guide.programs.map {
                                    it.title
                                },
                            )
                            assertTrue(guide.programs.all { it.channelId == "hallmark_christmas" })
                            assertEquals(
                                1,
                                fixture.hits("/catalog/channels"),
                                "the catalog is shared by the listing and the guide",
                            )
                            assertFailsWith<ProviderFailure> {
                                client.resolve("hallmark_western")
                            }.let { assertFalse(it.transient) }
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `an unreachable catalog still lists the channels without logos`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hallmark-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        val client =
                            HallmarkClient(
                                ProviderFixture.context(
                                    "hallmark",
                                    mapOf("apiBase" to "${fixture.base}/catalog"),
                                    http,
                                    store,
                                    MutableClock(),
                                ),
                            )
                        val channels = client.channels()
                        assertEquals(4, channels.size)
                        assertTrue(channels.all { it.logo == null })
                        assertFailsWith<ProviderFailure> { client.guide(setOf("hallmark_hits")) }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
