package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.providerState
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

class PlutoClientTest {
    private val trending = "673247127d5da5000817b4d6"
    private val spotlight = "5ba3fb9c4b078e0f37ad34e8"

    private fun ProviderFixture.pluto() {
        json("/boot", ProviderFixture.resource("pluto-boot.json").replace("BASE", base))
        fun guarded(body: () -> String) = { exchange: com.sun.net.httpserver.HttpExchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer fixture-jwt") {
                Canned("""{"error":"unauthorized"}""", 401)
            } else {
                Canned(body())
            }
        }
        route("/channels/v2/guide/channels", guarded { ProviderFixture.resource("pluto-channels.json") })
        route("/channels/v2/guide/categories", guarded { ProviderFixture.resource("pluto-categories.json") })
        route("/channels/v2/guide/timelines", guarded { ProviderFixture.resource("pluto-timelines.json") })
    }

    private fun options(fixture: ProviderFixture) = mapOf("bootUrl" to "${fixture.base}/boot")

    @Test fun `lists channels without the registered-only ones and groups them by their first category`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-pluto-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.pluto()
                            val client =
                                PlutoClient(
                                    ProviderFixture.context("pluto", options(fixture), http, store, MutableClock()),
                                )
                            val channels = client.channels()
                            assertEquals(
                                listOf(trending, spotlight),
                                channels.map {
                                    it.id
                                },
                                "the channel entitled to registered users is left out",
                            )
                            assertEquals("Pluto TV Trending Now", channels[0].name)
                            assertEquals("5", channels[0].number)
                            assertEquals("Movies", channels[0].group)
                            assertNull(channels[1].group, "an unknown category names no group")
                            assertTrue(
                                channels[0].logo!!.startsWith(
                                    "https://images.pluto.tv/channels/$trending/colorLogoPNG",
                                ),
                            )
                            val deviceId = store.providerState("pluto", ProviderContext.DEVICE_ID)!!.value
                            assertTrue(
                                fixture.lastQuery["/boot"]!!.contains(
                                    "&clientID=$deviceId&clientTime=2026-10-10T13%3A00%3A00Z",
                                ),
                                fixture.lastQuery["/boot"],
                            )
                            assertEquals(1, fixture.hits("/boot"))
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a refused session boots again and resolve builds the stitcher URL from the boot answer`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-pluto-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.pluto()
                        val clock = MutableClock()
                        val stale = """{"token":"stale","channels":"${fixture.base}/channels",""" +
                            """"stitcher":"${fixture.base}/old","params":"appName=web"}"""
                        store.saveProviderState("pluto", "session", stale, clock.now.plusSeconds(600), clock.now)
                        val client = PlutoClient(ProviderFixture.context("pluto", options(fixture), http, store, clock))
                        val resolved = client.resolve(trending)
                        assertEquals(1, fixture.hits("/boot"))
                        assertEquals(
                            "${fixture.base}/stitcher/v2/stitch/hls/channel/$trending/master.m3u8?appName=web&" +
                                "clientID=fixture-client&country=US&deviceType=web&serverSideAds=false" +
                                "&jwt=fixture-jwt&masterJWTPassthrough=true&includeExtendedEvents=true",
                            resolved.url,
                        )
                        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
                        assertEquals("https://pluto.tv", resolved.headers["Origin"])
                        assertFailsWith<ProviderFailure> {
                            client.resolve("5b4e94282d4ec87bdcbb87cd")
                        }.let { assertFalse(it.transient) }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide asks the timelines for mapped channels only`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-pluto-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.pluto()
                        val client =
                            PlutoClient(ProviderFixture.context("pluto", options(fixture), http, store, MutableClock()))
                        assertTrue(client.guide(emptySet()).programs.isEmpty())
                        assertEquals(0, fixture.hits("/channels/v2/guide/timelines"))
                        val guide = client.guide(setOf(trending))
                        assertEquals(2, guide.channels.size)
                        assertEquals(
                            "start=2026-10-10T13:00:00Z&channelIds=$trending&duration=720",
                            fixture.lastQuery["/channels/v2/guide/timelines"],
                        )
                        assertEquals(listOf("John Tucker Must Die", "Mean Girls"), guide.programs.map { it.title })
                        assertEquals("2026-10-11T03:30:00Z", guide.programs[0].startsAt.toString())
                        assertTrue(
                            guide.programs.all {
                                it.subTitle == null && it.episodeNumbers.isEmpty() && it.rating == null
                            },
                            "a timeline with only a description adds nothing",
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide maps the episode's name, numbers, genres, rating and poster, and a film is a movie`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-pluto-")
            try {
                ProviderFixture().use { fixture ->
                    ProviderFixture.httpClient().use { http ->
                        openStore(dir).use { store ->
                            fixture.pluto()
                            fixture.route("/channels/v2/guide/timelines") {
                                Canned(ProviderFixture.resource("pluto-timelines-episode.json"))
                            }
                            val client =
                                PlutoClient(
                                    ProviderFixture.context("pluto", options(fixture), http, store, MutableClock()),
                                )
                            val (episode, film) = client.guide(setOf(trending)).programs.map { it.bounded() }
                            assertEquals("Sample Series", episode.title)
                            assertEquals("The Sample Episode", episode.subTitle)
                            assertEquals("Something happens.", episode.description)
                            assertEquals(
                                listOf(EpisodeNumber("xmltv_ns", "1.4."), EpisodeNumber("onscreen", "S02E05")),
                                episode.episodeNumbers,
                            )
                            assertEquals(listOf("Comedy", "Sitcoms"), episode.categories)
                            assertEquals(ProgramRating("TV-14", "VCHIP"), episode.rating)
                            assertEquals("https://images.pluto.tv/episodes/sample/poster.jpg", episode.icon)
                            assertEquals("Sample Film", film.title)
                            assertNull(film.subTitle)
                            assertTrue(
                                film.episodeNumbers.isEmpty(),
                                "a film's season and number are not episode numbering",
                            )
                            assertEquals(listOf("Movie", "Drama"), film.categories)
                            assertNull(film.rating, "Not Rated is no rating")
                        }
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
}
