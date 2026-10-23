package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VizioClientTest {
    private fun ProviderFixture.vizio() {
        json("/api/channels", ProviderFixture.resource("vizio-channels.json").replace("BASE", base))
        json("/api/airings/", ProviderFixture.resource("vizio-airings.json"))
    }

    @Test fun `lists clear channels, skips token-gated ones and fills both macro encodings`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-vizio-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.vizio()
                        val clock = MutableClock()
                        val client =
                            VizioClient(
                                ProviderFixture.context(
                                    "vizio",
                                    mapOf("apiBase" to "${fixture.base}/api"),
                                    http,
                                    store,
                                    clock,
                                ),
                            )
                        val channels = client.channels()
                        assertEquals(
                            listOf("93FoxNewsNow", "93cnnnw"),
                            channels.map {
                                it.id
                            },
                            "the NFL channel has a tokenUrl",
                        )
                        val fox = channels.first()
                        assertEquals("LiveNOW from FOX", fox.name)
                        assertEquals("200", fox.number)
                        assertEquals("News + opinion", fox.group)
                        assertEquals("https://images.vizio.com/v4/btv/1/1736242707/1/267/150/0.jpg", fox.logo)
                        assertEquals(listOf("us"), fox.tags)
                        assertEquals("okhttp/4.12.0", fixture.lastHeaders["/api/channels"]!!["user-agent"])
                        val resolved = client.resolve("93FoxNewsNow")
                        val deviceId = store.providerState("vizio", ProviderContext.DEVICE_ID)!!.value
                        assertEquals(
                            "${fixture.base}/vizio/master.m3u8?did=$deviceId&us_privacy=1---&app_name=VIZIO&w=1080&" +
                                "iu=&vizdartoken=fixture",
                            resolved.url,
                        )
                        assertEquals(
                            "${fixture.base}/vizio/other.m3u8?did=$deviceId&lmt=0",
                            client.resolve("93cnnnw").url,
                        )
                        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
                        assertEquals(
                            1,
                            fixture.hits("/api/channels"),
                            "channels, both resolves and the guide share one listing",
                        )
                        assertFailsWith<ProviderFailure> {
                            client.resolve("118NFLDigitalChannel1")
                        }.let { assertFalse(it.transient) }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide takes one bulk airings call and maps stations to channels`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-vizio-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.vizio()
                        val clock = MutableClock()
                        val client =
                            VizioClient(
                                ProviderFixture.context(
                                    "vizio",
                                    mapOf("apiBase" to "${fixture.base}/api"),
                                    http,
                                    store,
                                    clock,
                                ),
                            )
                        assertTrue(client.guide(emptySet()).programs.isEmpty())
                        assertEquals(0, fixture.hits("/api/airings/"), "nothing mapped, nothing fetched")
                        val guide = client.guide(setOf("93FoxNewsNow"))
                        assertEquals(2, guide.channels.size)
                        assertEquals(1, fixture.hits("/api/airings/"))
                        val query = fixture.lastQuery["/api/airings/"]!!
                        assertTrue(
                            query.startsWith(
                                "start=2026-10-10T13:00:00.000Z&end=2026-10-11T13:00:00.000Z&" +
                                    "startChannel=93FoxNewsNow&channelCount=2",
                            ),
                            query,
                        )
                        assertEquals(2, guide.programs.size, "the airing of an unknown station is dropped")
                        assertTrue(
                            guide.programs.all {
                                it.channelId == "93FoxNewsNow" && it.title == "LiveNOW from FOX"
                            },
                        )
                        assertEquals("2026-10-11T02:00:00Z", guide.programs.first().startsAt.toString())
                        val first = guide.programs.first()
                        assertNull(first.subTitle, "a sub-title equal to the title says nothing")
                        assertTrue(first.episodeNumbers.isEmpty(), "season and episode 0 mean unnumbered")
                        assertEquals(
                            "LIVE 24/7. Always On. Raw & unfiltered breaking news, live events and stories from " +
                                "across the nation.",
                            first.description,
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide maps the airing's sub-title and season and episode numbers`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-vizio-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.vizio()
                        fixture.json("/api/airings/", ProviderFixture.resource("vizio-airings-episode.json"))
                        val client =
                            VizioClient(
                                ProviderFixture.context(
                                    "vizio",
                                    mapOf("apiBase" to "${fixture.base}/api"),
                                    http,
                                    store,
                                    MutableClock(),
                                ),
                            )
                        val (episode, special) = client.guide(setOf("93FoxNewsNow")).programs
                        assertEquals("Sample Series", episode.title)
                        assertEquals("The Sample Episode", episode.subTitle)
                        assertEquals("Something happens.", episode.description)
                        assertEquals(
                            listOf(EpisodeNumber("xmltv_ns", "1.4."), EpisodeNumber("onscreen", "S02E05")),
                            episode.episodeNumbers,
                        )
                        assertEquals(
                            listOf(EpisodeNumber("xmltv_ns", ".2.")),
                            special.episodeNumbers,
                            "an episode without a season",
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
