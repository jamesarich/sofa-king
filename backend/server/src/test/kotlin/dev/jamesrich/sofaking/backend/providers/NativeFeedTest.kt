package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.http.backendModule
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.MediaRelay
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.TsRemux
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/** A service client with no network of its own: it lists two channels and resolves them onto the fixture's HLS. */
private class FakeClient(private val base: String, private val clock: MutableClock) : ProviderClient {
    override val service = "whale"
    val resolves = AtomicInteger()
    var listing: List<ProviderChannel> = listOf(
        ProviderChannel("one", "Channel One", "101", "$base/logo.png", "News", listOf("us")),
        ProviderChannel("two", "Channel Two", null, null, null),
    )
    var failResolve: ProviderFailure? = null

    override suspend fun channels() = listing

    override suspend fun guide(wanted: Set<String>) = ParsedGuide(
        listing.map { GuideChannel(it.id, it.name) },
        if ("one" in wanted) listOf(GuideProgram("one", Instant.parse("2026-10-10T12:00:00Z"), Instant.parse("2026-10-10T14:00:00Z"), "Native program")) else emptyList(),
    )

    override suspend fun resolve(channelId: String): ResolvedStream {
        failResolve?.let { throw it }
        resolves.incrementAndGet()
        if (channelId != "one") throw ProviderFailure("gone", transient = false)
        return ResolvedStream("$base/master.m3u8?session=${resolves.get()}", mapOf("User-Agent" to "sofa-fixture-ua", "Origin" to "https://svc.test"), clock.now.plus(Duration.ofHours(1)))
    }
}

class NativeFeedTest {
    private val token = "secret".repeat(8)
    private val config = FeedConfig("whale", type = "native", service = "whale", probe = true)

    private suspend fun runtime(dir: java.nio.file.Path, fixture: Fixture, fake: FakeClient, clock: MutableClock, http: io.ktor.client.HttpClient): LiveRuntime {
        val store = openStore(dir)
        val policy = UpstreamPolicy(allowLoopback = true)
        val registry = ProviderRegistry(mapOf("whale" to fake), clock)
        val sources = SourceConfig(listOf(config))
        lateinit var logos: LogoCache
        val feeds = FeedService(sources.providers, store, http, clock, policy, registry) { logos.requestSweep() }
        feeds.initialize()
        lateinit var lineup: LineupService
        val guides = GuideService(sources.allGuides, store, http, clock, policy, registry) { lineup.wanted() }
        guides.initialize()
        lineup = LineupService(store, clock, feeds, guides)
        lineup.initialize()
        val catalog = CatalogService(feeds, guides, lineup, store, clock)
        logos = LogoCache(store, http, clock, dir.resolve("logos"), policy, { emptyList() })
        logos.initialize()
        val sessions = SessionManager(catalog, store, clock, 4)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return LiveRuntime(store, feeds, guides, lineup, catalog, sessions, MediaRelay(http, feeds, sessions, scope), TsRemux("ffmpeg", LoopbackRelay(http, policy)), logos, http, scope)
    }

    @Test fun `a native provider imports as svc offerings that the relay resolves with the service headers`() = testApplication {
        val dir = Files.createTempDirectory("sofa-native-")
        Fixture().use { fixture ->
            val clock = MutableClock()
            val fake = FakeClient(fixture.base, clock)
            val http = ProviderFixture.httpClient()
            val runtime = runtime(dir, fixture, fake, clock, http)
            try {
                runtime.feeds.refresh(config)
                val status = runtime.feeds.providers().single()
                assertEquals(2, status.channels); assertEquals(2, status.sources); assertNull(status.errorCode)
                val offerings = runtime.feeds.offerings()
                assertEquals(setOf("svc://whale/one", "svc://whale/two"), offerings.map { it.url }.toSet())
                assertTrue(offerings.all { it.protocol == "hls" })
                val one = runtime.feeds.channels().first { it.name == "Channel One" }
                assertEquals("101", one.displayNumber); assertEquals(listOf("News", "us"), one.tags); assertEquals("one", one.legacyGuideId)
                assertEquals("${fixture.base}/logo.png", one.logo)
                // The implicit native guide lists the channel, so adopting maps it.
                runtime.guides.refresh(runtime.guides.configs.single())
                assertEquals("whale", runtime.guides.configs.single().provider)
                runtime.lineup.adopt(AdoptProvider("whale"))
                val entry = runtime.lineup.entries().first { it.name == "Channel One" }
                assertEquals(GuideMapping("whale", "one"), entry.guide)
                runtime.guides.refresh(runtime.guides.configs.single())
                val window = runtime.catalog.guide(GuideQuery(listOf(entry.id), "2026-10-10T12:00:00Z", "2026-10-10T13:00:00Z"))
                assertEquals("Native program", window.channels.single().programs.single().title)

                application { backendModule(runtime.store, clock, runtime, token) }
                val session = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(entry.id, "watch", "k1"))) }.bodyAsText(),
                )
                assertEquals("hls", session.descriptor.protocol)
                val root = client.get(session.descriptor.url)
                assertEquals(HttpStatusCode.OK, root.status)
                val manifest = root.bodyAsText()
                assertTrue(manifest.startsWith("#EXTM3U") && manifest.contains("/media/sessions/"), manifest)
                assertFalse(manifest.contains("svc://") || manifest.contains(fixture.base), "nothing upstream leaks to the player")
                assertEquals(1, fake.resolves.get())
                // The media playlist is an absolute upstream URL by now; it still travels with the service headers.
                val media = Regex("/media/sessions/\\S+\\.m3u8").findAll(manifest).map { it.value }.first { it != session.descriptor.url }
                assertEquals(HttpStatusCode.OK, client.get(media).status)
                assertEquals(1, fake.resolves.get(), "the resolution is cached for the session")
                val second = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(entry.id, "watch", "k2"))) }.bodyAsText(),
                )
                assertEquals(HttpStatusCode.OK, client.get(second.descriptor.url).status)
                assertEquals(1, fake.resolves.get(), "a second session reuses the cached resolution")
                clock.now = clock.now.plus(Duration.ofMinutes(59).plusSeconds(1))
                val third = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(entry.id, "watch", "k3"))) }.bodyAsText(),
                )
                assertEquals(HttpStatusCode.OK, client.get(third.descriptor.url).status)
                assertEquals(2, fake.resolves.get(), "a minute before expiry the URL is minted again")

                val two = runtime.lineup.entries().first { it.name == "Channel Two" }
                val gone = Json.decodeFromString<PlaybackSession>(
                    client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(OpenPlayback(two.id, "watch", "k4"))) }.bodyAsText(),
                )
                val refused = client.get(gone.descriptor.url)
                assertEquals(HttpStatusCode.BadGateway, refused.status)
                assertEquals("UPSTREAM_UNAVAILABLE", Json.decodeFromString<ApiError>(refused.bodyAsText()).code)
            } finally { runtime.close(); runtime.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `probes qualify a native source at its resolved URL and class a dead service as provider evidence`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-native-probe-")
        Fixture().use { fixture ->
            val clock = MutableClock()
            val fake = FakeClient(fixture.base, clock)
            val http = ProviderFixture.httpClient()
            val runtime = runtime(dir, fixture, fake, clock, http)
            try {
                runtime.feeds.refresh(config)
                val seen = ArrayList<Pair<String, Map<String, String>>>()
                val worker = ProbeWorker(runtime.feeds, runtime.store, clock, "unused", config = ProbeConfig(parallelism = 1)) { offering, headers ->
                    seen += offering.url to headers
                    if (offering.url.startsWith("svc://")) FailureClass.OFFERING else null
                }
                assertTrue(worker.step()); assertTrue(worker.step()); assertFalse(worker.step())
                val one = runtime.feeds.offerings().first { it.url == "svc://whale/one" }
                val two = runtime.feeds.offerings().first { it.url == "svc://whale/two" }
                assertEquals(HealthStatus.HEALTHY, runtime.store.health(one.id)!!.status)
                assertEquals("${fixture.base}/master.m3u8?session=1" to mapOf("User-Agent" to "sofa-fixture-ua", "Origin" to "https://svc.test"), seen.single())
                assertEquals(HealthStatus.SUSPECT, runtime.store.health(two.id)!!.status, "a channel the service dropped is offering evidence")
                fake.failResolve = ProviderFailure("api down")
                clock.now = clock.now.plus(Duration.ofHours(7))
                assertTrue(worker.step()); assertTrue(worker.step())
                assertEquals(HealthStatus.HEALTHY, runtime.store.health(one.id)!!.status, "cached resolution still serves the probe")
                assertEquals(HealthStatus.SUSPECT, runtime.store.health(two.id)!!.status)
                assertTrue(runtime.store.health(two.id)!!.failures.size == 1, "a service outage adds no strike")
            } finally { runtime.close(); runtime.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `native configuration validates the service and gets an implicit guide`() {
        val whale = FeedConfig("w", type = "native", service = "whale")
        whale.validate()
        assertEquals(3600, whale.refreshInterval)
        val guides = SourceConfig(listOf(whale, FeedConfig("s", type = "native", service = "samsung", options = mapOf("region" to "us")))).allGuides
        assertEquals("w", guides[0].provider); assertNull(guides[0].url)
        assertEquals("https://i.mjh.nz/SamsungTVPlus/us.xml.gz", guides[1].url); assertNull(guides[1].provider)
        assertFailsWith<IllegalArgumentException> { FeedConfig("x", type = "native", service = "nope").validate() }
        assertFailsWith<IllegalArgumentException> { FeedConfig("x", type = "native").validate() }
        assertFailsWith<IllegalArgumentException> { FeedConfig("x", "http://feeds.test/p.m3u", service = "whale").validate() }
        assertFailsWith<IllegalArgumentException> { FeedConfig("x", type = "native", service = "whale", playlistUrl = "http://feeds.test/p.m3u").validate() }
        assertFailsWith<IllegalArgumentException> { FeedConfig("x").validate() }
        assertEquals("svc://pbs/4b65%3Aga-create", NativeServices.offeringUrl("pbs", "4b65:ga-create"))
        assertEquals("4b65:ga-create", NativeServices.channelId("svc://pbs/4b65%3Aga-create"))
        for (id in listOf("news 24", "a+b", "x/y?z#w")) assertEquals(id, NativeServices.channelId(NativeServices.offeringUrl("svc", id)), id)
    }
}
