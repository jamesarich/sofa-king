package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.providerState
import dev.jamesrich.sofaking.backend.store.saveProviderState
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlexClientTest {
    private val cw = "66635fc0ca7b22f4c3a2d743"
    private val amc = "5fc705e2a2236e002d69f3ba"
    private val cwPart = "/library/parts/6a1610bebdf296985fd95603-$cw.m3u8"

    private fun ProviderFixture.plex(token: String = "fixture-token") {
        route("/auth") { Canned("""{"id":1,"anonymous":true,"authToken":"$token"}""", 201) }
        fun guarded(body: () -> String) = { exchange: com.sun.net.httpserver.HttpExchange ->
            if (exchange.requestHeaders.getFirst("X-Plex-Token") != token) Canned("""{"error":"unauthorized"}""", 401) else Canned(body())
        }
        route("/epg/lineups/plex/channels", guarded {
            when (lastQuery["/epg/lineups/plex/channels"]?.substringBefore("&")) {
                "genre=movies" -> """{"MediaContainer":{"Channel":[{"gridKey":"$amc"}]}}"""
                "genre=bingeworthy" -> """{"MediaContainer":{"Channel":[{"gridKey":"$cw"},{"gridKey":"$amc"}]}}"""
                else -> ProviderFixture.resource("plex-channels.json")
            }
        })
        route("/epg/", guarded { ProviderFixture.resource("plex-root.json") })
        route("/epg/grid", guarded { ProviderFixture.resource("plex-grid.json") })
        route("/epg$cwPart") { exchange ->
            if (exchange.requestURI.rawQuery?.contains("X-Plex-Token=$token") == true) Canned(ProviderFixture.MASTER, contentType = "application/vnd.apple.mpegurl")
            else Canned("", 401)
        }
    }

    private fun options(fixture: ProviderFixture) = mapOf("apiBase" to "${fixture.base}/epg", "authUrl" to "${fixture.base}/auth")

    @Test fun `lists clear channels by grid key with groups from the themed genres first`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-plex-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.plex()
            val clock = MutableClock()
            val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
            val channels = client.channels()
            assertEquals(listOf(cw, amc), channels.map { it.id }, "the channel marked drm is left out")
            assertEquals("CW Forever", channels[0].name); assertNull(channels[0].number)
            assertEquals("Bingeworthy", channels[0].group, "only the catch-all genre lists it")
            assertEquals("Movies", channels[1].group, "a themed genre wins over the catch-all")
            assertTrue(channels[0].logo!!.startsWith("https://provider-static.plex.tv/"))
            assertEquals(1, fixture.hits("/auth"))
            val deviceId = store.providerState("plex", ProviderContext.DEVICE_ID)!!.value
            assertEquals(deviceId, fixture.lastHeaders["/auth"]!!["x-plex-client-identifier"])
            assertEquals("fixture-token", store.providerState("plex", "token")!!.value)
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a rejected token is replaced once and resolve returns the part master with its token`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-plex-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.plex(token = "fixture-token-2")
            val clock = MutableClock()
            store.saveProviderState("plex", "token", "stale-token", null, clock.now)
            val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
            val resolved = client.resolve(cw)
            assertEquals(1, fixture.hits("/auth"))
            assertEquals("${fixture.base}/epg$cwPart?includeAllStreams=1&X-Plex-Product=Plex+Mediaverse&X-Plex-Token=fixture-token-2", resolved.url)
            assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
            assertEquals("https://watch.plex.tv", resolved.headers["Origin"])
            assertFailsWith<ProviderFailure> { client.resolve("6244cf3c742b0d58cd4f2514") }.let { assertFalse(it.transient, "a DRM channel is not listed") }
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `guide asks the grid for mapped channels only, today and tomorrow`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-plex-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.plex()
            val clock = MutableClock()
            val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, clock))
            assertTrue(client.guide(emptySet()).programmes.isEmpty())
            assertEquals(0, fixture.hits("/epg/grid"))
            val guide = client.guide(setOf(cw))
            assertEquals(2, guide.channels.size)
            assertEquals(2, fixture.hits("/epg/grid"))
            assertTrue(fixture.lastQuery["/epg/grid"]!!.startsWith("channelGridKey=$cw&date=2026-10-11"), fixture.lastQuery["/epg/grid"])
            assertEquals(2, guide.programmes.size, "the same airing on both days is kept once")
            assertEquals("7th Heaven", guide.programmes[0].title)
            assertEquals("2026-10-10T23:27:56Z", guide.programmes[0].startsAt.toString())
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `the guide waits out a 429 from the grid, and gives up after three waits`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-plex-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.plex()
            val limited = java.util.concurrent.atomic.AtomicInteger(2)
            fixture.route("/epg/grid") { if (limited.getAndDecrement() > 0) Canned("", 429) else Canned(ProviderFixture.resource("plex-grid.json")) }
            val fast = options(fixture) + mapOf("rateLimitWaitMillis" to "1", "gridSpacingMillis" to "0")
            val client = PlexClient(ProviderFixture.context("plex", fast, http, store, MutableClock()))
            assertEquals(2, client.guide(setOf(cw)).programmes.size)
            assertEquals(4, fixture.hits("/epg/grid"), "two 429s, then today and tomorrow")
            limited.set(Int.MAX_VALUE)
            assertFailsWith<ProviderFailure> { client.guide(setOf(cw)) }.let { assertTrue(it.transient) }
            assertEquals(8, fixture.hits("/epg/grid"), "the first request and three retries")
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a part Plex answers 400 for fails resolve for good, and resolve never waits out a 429`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-plex-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.plex()
            val client = PlexClient(ProviderFixture.context("plex", options(fixture), http, store, MutableClock()))
            fixture.route("/epg$cwPart") { Canned("", 400) }
            assertFailsWith<ProviderFailure> { client.resolve(cw) }.let { assertFalse(it.transient) }
            fixture.route("/epg$cwPart") { Canned("", 429) }
            assertFailsWith<ProviderFailure> { client.resolve(cw) }.let { assertTrue(it.transient) }
            assertEquals(2, fixture.hits("/epg$cwPart"))
            fixture.route("/epg$cwPart") { Canned(302, location = "https://cdn.example/live/master.m3u8") }
            assertEquals("https://cdn.example/live/master.m3u8", client.resolve(cw).url, "a part that redirects resolves to its target")
            fixture.route("/epg$cwPart") { Canned(302, location = "/cdn/master.m3u8") }
            assertEquals("${fixture.base}/cdn/master.m3u8", client.resolve(cw).url, "a relative redirect is resolved against the part")
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}
