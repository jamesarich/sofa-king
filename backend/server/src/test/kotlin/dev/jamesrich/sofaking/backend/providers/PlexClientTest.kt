package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.store.providerState
import dev.jamesrich.sofaking.backend.store.saveProviderState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexClientTest {
    private val cw = "66635fc0ca7b22f4c3a2d743"
    private val amc = "5fc705e2a2236e002d69f3ba"
    private val cwPart = "/library/parts/6a1610bebdf296985fd95603-$cw.m3u8"

    private fun ProviderFixture.plex(token: String = "fixture-token") {
        route("/auth") { Canned("""{"id":1,"anonymous":true,"authToken":"$token"}""", 201) }
        fun guarded(body: () -> String) = { exchange: com.sun.net.httpserver.HttpExchange ->
            if (exchange.requestHeaders.getFirst("X-Plex-Token") != token) {
                Canned("""{"error":"unauthorized"}""", 401)
            } else {
                Canned(body())
            }
        }
        route(
            "/epg/lineups/plex/channels",
            guarded {
                when (lastQuery["/epg/lineups/plex/channels"]?.substringBefore("&")) {
                    "genre=movies" -> """{"MediaContainer":{"Channel":[{"gridKey":"$amc"}]}}"""
                    "genre=bingeworthy" -> """{"MediaContainer":{"Channel":[{"gridKey":"$cw"},{"gridKey":"$amc"}]}}"""
                    else -> ProviderFixture.resource("plex-channels.json")
                }
            },
        )
        route("/epg/", guarded { ProviderFixture.resource("plex-root.json") })
        route("/epg/grid", guarded { ProviderFixture.resource("plex-grid.json") })
        route("/epg$cwPart") { exchange ->
            if (exchange.requestURI.rawQuery?.contains("X-Plex-Token=$token") == true) {
                Canned(ProviderFixture.MASTER, contentType = "application/vnd.apple.mpegurl")
            } else {
                Canned("", 401)
            }
        }
    }

    private fun options(fixture: ProviderFixture) = mapOf(
        "apiBase" to "${fixture.base}/epg",
        "authUrl" to "${fixture.base}/auth",
    )

    @Test fun `lists clear channels by grid key with groups from the themed genres first`() = providerTest(
        "sofa-plex-",
    ) {
        fixture.plex()
        val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
        val channels = client.channels()
        assertEquals(listOf(cw, amc), channels.map { it.id }, "the channel marked drm is left out")
        assertEquals("CW Forever", channels[0].name)
        assertNull(channels[0].number)
        assertEquals("Bingeworthy", channels[0].group, "only the catch-all genre lists it")
        assertEquals("Movies", channels[1].group, "a themed genre wins over the catch-all")
        assertTrue(channels[0].logo!!.startsWith("https://provider-static.plex.tv/"))
        assertEquals(1, fixture.hits("/auth"))
        val deviceId = store.providerState("plex", ProviderContext.DEVICE_ID)!!.value
        assertEquals(deviceId, fixture.lastHeaders["/auth"]!!["x-plex-client-identifier"])
        assertEquals("fixture-token", store.providerState("plex", "token")!!.value)
    }

    @Test fun `a failed genre fetch keeps the groups the last one found, across a restart`() = providerTest(
        "sofa-plex-",
    ) {
        fixture.plex()
        val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
        assertEquals(listOf("Bingeworthy", "Movies"), client.channels().map { it.group })
        fixture.route("/epg/") { Canned("{}", 500) }
        // A new client over the same store is the server after a restart,
        // with nothing fetched in memory.
        val restarted =
            PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
        assertEquals(
            listOf("Bingeworthy", "Movies"),
            restarted.channels().map { it.group },
            "a lost group would move the station's number",
        )
    }

    @Test
    fun `a rejected token is replaced once and resolve returns the part master with its token`() =
        providerTest("sofa-plex-") {
            fixture.plex(token = "fixture-token-2")
            store.saveProviderState("plex", "token", "stale-token", null, clock.now)
            val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
            val resolved = client.resolve(cw)
            assertEquals(1, fixture.hits("/auth"))
            assertEquals(
                "${fixture.base}/epg$cwPart?includeAllStreams=1&X-Plex-Product=Plex+Mediaverse&" +
                    "X-Plex-Token=fixture-token-2",
                resolved.url,
            )
            assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
            assertEquals("https://watch.plex.tv", resolved.headers["Origin"])
            assertFailsWith<ProviderFailure> {
                client.resolve("6244cf3c742b0d58cd4f2514")
            }.let { assertFalse(it.transient, "a DRM channel is not listed") }
        }

    @Test fun `guide asks the grid for mapped channels only, today and tomorrow`() = providerTest("sofa-plex-") {
        fixture.plex()
        val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
        assertTrue(client.guide(emptySet()).programs.isEmpty())
        assertEquals(0, fixture.hits("/epg/grid"))
        val guide = client.guide(setOf(cw))
        assertEquals(2, guide.channels.size)
        assertEquals(2, fixture.hits("/epg/grid"))
        assertTrue(
            fixture.lastQuery["/epg/grid"]!!.startsWith("channelGridKey=$cw&date=2026-10-11"),
            fixture.lastQuery["/epg/grid"],
        )
        assertEquals(2, guide.programs.size, "the same airing on both days is kept once")
        assertEquals("7th Heaven", guide.programs[0].title)
        assertEquals("2026-10-10T23:27:56Z", guide.programs[0].startsAt.toString())
        assertEquals(
            "We the People",
            guide.programs[0].subTitle,
            "an episode's own title is the sub-title",
        )
    }

    @Test fun `guide maps episode numbers, genres, rating, image, date and premieres`() = providerTest("sofa-plex-") {
        fixture.plex()
        fixture.route("/epg/grid") { Canned(ProviderFixture.resource("plex-grid-episode.json")) }
        val client =
            PlexClient(context("plex", options(fixture)))
        val (episode, movie) = client.guide(setOf(cw)).programs.map { it.bounded() }
        assertEquals("7th Heaven", episode.title)
        assertEquals("We the People", episode.subTitle)
        assertEquals(
            listOf(EpisodeNumber("xmltv_ns", "5.11."), EpisodeNumber("onscreen", "S06E12")),
            episode.episodeNumbers,
        )
        assertEquals(listOf("Drama", "Family"), episode.categories)
        assertEquals(ProgramRating("TV-G", "VCHIP"), episode.rating)
        assertEquals("https://provider-static.plex.tv/epg/images/thumb.jpg", episode.icon)
        assertEquals("20020128", episode.date)
        assertTrue(episode.isNew)
        assertEquals("Sample Film", movie.title)
        assertNull(movie.subTitle)
        assertTrue(movie.episodeNumbers.isEmpty())
        assertEquals(listOf("Movie"), movie.categories)
        assertEquals(ProgramRating("PG-13", "MPAA"), movie.rating)
        assertEquals("1999", movie.date)
        assertFalse(movie.isNew)
    }

    @Test fun `the guide waits out a 429 from the grid, and gives up after three waits`() = providerTest("sofa-plex-") {
        fixture.plex()
        val limited = java.util.concurrent.atomic.AtomicInteger(2)
        fixture.route("/epg/grid") {
            if (limited.getAndDecrement() > 0) {
                Canned("", 429)
            } else {
                Canned(ProviderFixture.resource("plex-grid.json"))
            }
        }
        val fast = options(fixture) + mapOf("rateLimitWaitMillis" to "1", "gridSpacingMillis" to "0")
        val client = PlexClient(context("plex", fast))
        assertEquals(2, client.guide(setOf(cw)).programs.size)
        assertEquals(4, fixture.hits("/epg/grid"), "two 429s, then today and tomorrow")
        limited.set(Int.MAX_VALUE)
        assertFailsWith<ProviderFailure> { client.guide(setOf(cw)) }.let { assertTrue(it.transient) }
        assertEquals(8, fixture.hits("/epg/grid"), "the first request and three retries")
    }

    @Test
    fun `a part Plex answers 400 for fails resolve for good, and resolve never waits out a 429`() =
        providerTest("sofa-plex-") {
            fixture.plex()
            val client =
                PlexClient(context("plex", options(fixture)))
            fixture.route("/epg$cwPart") { Canned("", 400) }
            assertFailsWith<ProviderFailure> { client.resolve(cw) }.let { assertFalse(it.transient) }
            fixture.route("/epg$cwPart") { Canned("", 429) }
            assertFailsWith<ProviderFailure> { client.resolve(cw) }.let { assertTrue(it.transient) }
            assertEquals(2, fixture.hits("/epg$cwPart"))
            fixture.route("/epg$cwPart") { Canned(302, location = "https://cdn.example/live/master.m3u8") }
            assertEquals(
                "https://cdn.example/live/master.m3u8",
                client.resolve(cw).url,
                "a part that redirects resolves to its target",
            )
            fixture.route("/epg$cwPart") { Canned(302, location = "/cdn/master.m3u8") }
            assertEquals(
                "${fixture.base}/cdn/master.m3u8",
                client.resolve(cw).url,
                "a relative redirect is resolved against the part",
            )
        }

    @Test fun `a regional provider sends its forwarded address on every request and tags channels with its region`() =
        providerTest("sofa-plex-") {
            fixture.plex()
            val regional = options(fixture) + mapOf("region" to "ca", "forwardedFor" to "2001:db8::7")
            val client =
                PlexClient(context("plex-ca", regional))
            assertTrue(client.channels().all { it.tags == listOf("ca") })
            for (path in listOf("/auth", "/epg/lineups/plex/channels", "/epg/")) {
                assertEquals("2001:db8::7", fixture.lastHeaders[path]!!["x-forwarded-for"], path)
            }
            val resolved = client.resolve(cw)
            assertEquals("2001:db8::7", fixture.lastHeaders["/epg$cwPart"]!!["x-forwarded-for"])
            assertEquals(
                "2001:db8::7",
                resolved.headers["X-Forwarded-For"],
                "the relay sends it on every stream request too",
            )
        }
}
