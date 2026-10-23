package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StirrClientTest {
    private fun ProviderFixture.stirr() {
        json("/api/videos/list/", ProviderFixture.resource("stirr-channels.json"))
        json("/api/epg", ProviderFixture.resource("stirr-epg.json"))
        route("/api/v2/videos/5735/playable") { exchange ->
            if (exchange.requestMethod != "POST") {
                Canned("", 405)
            } else {
                Canned(ProviderFixture.resource("stirr-playable.json").replace("BASE", base))
            }
        }
        route("/api/v2/videos/5407/playable") { Canned("""{"status":404,"message":"Not found"}""", 404) }
    }

    @Test fun `lists each live channel once by number without DRM channels`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-stirr-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.stirr()
                        val client =
                            StirrClient(
                                ProviderFixture.context(
                                    "stirr",
                                    mapOf("apiBase" to "${fixture.base}/api"),
                                    http,
                                    store,
                                    MutableClock(),
                                ),
                            )
                        val channels = client.channels()
                        assertEquals(
                            listOf("5407", "5735"),
                            channels.map {
                                it.id
                            },
                            "the drm_protected channel is left out, the repeat is listed once",
                        )
                        val gags = channels[1]
                        assertEquals("Gags - Just for Laughs", gags.name, "runs of spaces collapse")
                        assertEquals("90", gags.number)
                        assertEquals("Comedy Live", gags.group)
                        assertEquals(listOf("us"), gags.tags)
                        assertTrue(gags.logo!!.endsWith(".png"))
                        assertTrue(
                            channels[0].logo!!.endsWith("-300x300-square.png"),
                            "a channel without a logo takes its square thumb",
                        )
                        assertEquals("https://stirr.com", fixture.lastHeaders["/api/videos/list/"]!!["origin"])
                        assertEquals(
                            "categories=all_categories&content_type=4&no_limit=true",
                            fixture.lastQuery["/api/videos/list/"],
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `resolve posts for the playable URL and blanks its nonce macro`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-stirr-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.stirr()
                        val clock = MutableClock()
                        val client =
                            StirrClient(
                                ProviderFixture.context(
                                    "stirr",
                                    mapOf("apiBase" to "${fixture.base}/api"),
                                    http,
                                    store,
                                    clock,
                                ),
                            )
                        val resolved = client.resolve("5735")
                        assertEquals(
                            "${fixture.base}/ssai/stream.m3u8?cb=1&AV_APPNAME=STIRR&AV_CONTENT_ID=5735&AV_NONCE=&" +
                                "vx_token=fixture-token",
                            resolved.url,
                        )
                        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
                        assertEquals("https://stirr.com/", resolved.headers["Referer"])
                        assertFailsWith<ProviderFailure> {
                            client.resolve("5407")
                        }.let { assertFalse(it.transient, "a 404 from playable means the channel is gone") }
                        assertFailsWith<ProviderFailure> { client.resolve("6435") }.let { assertFalse(it.transient) }
                        assertEquals(2, fixture.hits("/api/videos/list/"), "an unknown id refreshes the list once")
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide lists listed channels that have programs and keeps mapped ones that have not ended`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-stirr-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.stirr()
                            val clock = MutableClock(Instant.parse("2026-10-11T00:30:00Z"))
                            val client =
                                StirrClient(
                                    ProviderFixture.context(
                                        "stirr",
                                        mapOf("apiBase" to "${fixture.base}/api"),
                                        http,
                                        store,
                                        clock,
                                    ),
                                )
                            val unmapped = client.guide(emptySet())
                            assertTrue(unmapped.programs.isEmpty())
                            assertEquals(
                                listOf("5735"),
                                unmapped.channels.map {
                                    it.id
                                },
                                "a channel without programs or outside the list is not offered",
                            )
                            val guide = client.guide(setOf("5735", "5407"))
                            assertEquals(listOf("5735"), guide.channels.map { it.id })
                            assertEquals("tz=UTC", fixture.lastQuery["/api/epg"])
                            assertEquals(2, fixture.hits("/api/epg"), "one request per refresh")
                            assertEquals(2, guide.programs.size, "the program that ended at 00:11 is gone")
                            assertEquals("2026-10-11T00:26:42Z", guide.programs[0].startsAt.toString())
                            assertEquals("Just For Laughs Presents: Funny As Hell", guide.programs[0].title)
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
