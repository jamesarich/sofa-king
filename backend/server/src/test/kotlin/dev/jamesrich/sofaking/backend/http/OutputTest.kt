package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig

import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.query
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.date
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputTest {
    private val token = "secret".repeat(8)
    private val output = "players-" + "x".repeat(32)
    private val base = "http://localhost:80"

    private class Served(val runtime: LiveRuntime, val store: BackendStore, val clock: MutableClock, val channels: List<Channel>)

    /** The fixture's HLS station (5, News, guided) and TS station (6) adopted into the lineup, served with the output token. */
    private suspend fun ApplicationTestBuilder.serve(fixture: Fixture, dir: java.nio.file.Path, feeds: List<FeedConfig>, maxSessions: Int = 4): Served {
        val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(feeds))
        val clock = MutableClock()
        val store = openStore(dir)
        val outputs = OutputConfig(output, 3)
        val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, maxSessions = maxSessions, allowLoopbackUpstreams = true, outputs = outputs), clock)
        feeds.forEach { runtime.feeds.refresh(it) }; runtime.adoptAll()
        application { backendModule(store, clock, runtime, token, outputs = outputs) }
        val channels = Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels
        return Served(runtime, store, clock, channels)
    }

    @Test fun `playlist and guide serve the enabled lineup to the output token in the query or the API bearer`() = testApplication {
        val dir = Files.createTempDirectory("sofa-output-lists-")
        Fixture().use { fixture ->
            val served = serve(fixture, dir, listOf(fixture.feed, fixture.tsFeed))
            try {
                val hls = served.channels.single { it.displayNumber == "5" }
                val ts = served.channels.single { it.displayNumber == "6" }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/output/playlist.m3u").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/output/playlist.m3u?token=wrong").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/output/guide.xml") { bearerAuth("wrong") }.status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/output/stream/${hls.id}.m3u8").status)
                // The API token opens the outputs as a bearer only; in a query it would leak into player configs.
                assertEquals(HttpStatusCode.Unauthorized, client.get("/output/playlist.m3u?token=$token").status)
                assertEquals(HttpStatusCode.OK, client.get("/output/playlist.m3u") { bearerAuth(token) }.status)

                val playlist = client.get("/output/playlist.m3u?token=$output")
                assertEquals(HttpStatusCode.OK, playlist.status)
                assertTrue(playlist.headers[HttpHeaders.ContentType]!!.startsWith("audio/x-mpegurl"))
                val body = playlist.bodyAsText()
                val lines = body.lines()
                assertEquals("#EXTM3U url-tvg=\"$base/output/guide.xml?token=$output\" x-tvg-url=\"$base/output/guide.xml?token=$output\"", lines[0])
                assertEquals("#EXTINF:-1 tvg-id=\"${hls.id}\" tvg-chno=\"5\" tvg-name=\"Station\" group-title=\"News\",Station", lines[1])
                assertEquals("$base/output/stream/${hls.id}.m3u8?token=$output", lines[2])
                assertEquals("#EXTINF:-1 tvg-id=\"${ts.id}\" tvg-chno=\"6\" tvg-name=\"TS Station\",TS Station", lines[3])
                assertEquals("$base/output/stream/${ts.id}.ts?token=$output", lines[4])
                assertFalse(body.contains(fixture.base))

                val guide = client.get("/output/guide.xml?token=$output&days=2")
                assertEquals(HttpStatusCode.OK, guide.status)
                assertTrue(guide.headers[HttpHeaders.ContentType]!!.startsWith("application/xml"))
                val xml = guide.bodyAsText()
                assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv "))
                assertTrue(xml.contains("<channel id=\"${hls.id}\"><display-name>Station</display-name><display-name>5</display-name></channel>"))
                assertTrue(xml.contains("<channel id=\"${ts.id}\"><display-name>TS Station</display-name><display-name>6</display-name></channel>"))
                assertTrue(xml.contains(
                    "<programme start=\"20261010130000 +0000\" stop=\"20261010140000 +0000\" channel=\"${hls.id}\"><title>Morning</title>" +
                        "<category lang=\"en\">News</category><episode-num system=\"original-air-date\">2026-10-10 13:00:00</episode-num></programme>",
                ))
                assertFalse(xml.contains("Elsewhere"))
                assertTrue(xml.contains(
                    "<programme start=\"20261010100000 +0000\" stop=\"20261010110000 +0000\" channel=\"${ts.id}\"><title>TS Station</title>" +
                        "<category lang=\"en\">Series</category><episode-num system=\"original-air-date\">2026-10-10 10:00:00</episode-num></programme>",
                ), "a channel with no programmes gets hourly placeholders")
                assertFalse(xml.contains("channel=\"${hls.id}\"><title>Station</title>"), "a channel with programmes gets none")
                assertTrue(xml.trimEnd().endsWith("</tv>"))
                for (path in listOf("/output/guide.xml?token=$output", "/output/playlist.m3u?token=$output")) {
                    val gzipped = client.get(path) { header(HttpHeaders.AcceptEncoding, "gzip") }
                    assertEquals("gzip", gzipped.headers[HttpHeaders.ContentEncoding], path)
                    val plain = java.util.zip.GZIPInputStream(gzipped.bodyAsBytes().inputStream()).use { it.readAllBytes().decodeToString() }
                    assertEquals(client.get(path).bodyAsText().substringBefore('\n'), plain.substringBefore('\n'), path)
                }
                assertEquals(HttpStatusCode.BadRequest, client.get("/output/guide.xml?token=$output&days=0").status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/output/guide.xml?token=$output&days=15").status)

                client.patch("/api/v1/admin/lineup/${ts.id}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"enabled":false}""") }
                assertFalse(client.get("/output/playlist.m3u?token=$output").bodyAsText().contains(ts.id))
                assertFalse(client.get("/output/guide.xml?token=$output").bodyAsText().contains(ts.id))
                assertEquals(HttpStatusCode.NotFound, client.get("/output/stream/${ts.id}.ts?token=$output").status)
            } finally { served.runtime.close(); served.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `a guide's own numbering and metadata reach the XMLTV and the JSON guide without a synthesized air date`() = testApplication {
        val dir = Files.createTempDirectory("sofa-output-metadata-")
        Fixture().use { fixture ->
            fixture.guideMetadata = true
            val served = serve(fixture, dir, listOf(fixture.feed))
            try {
                val hls = served.channels.single()
                val xml = client.get("/output/guide.xml?token=$output&days=2").bodyAsText()
                assertTrue(xml.contains(
                    "<programme start=\"20261010130000 +0000\" stop=\"20261010140000 +0000\" channel=\"${hls.id}\"><title>Morning</title>" +
                        "<sub-title>Pilot &amp; Plans</sub-title><category>Sports</category>" +
                        "<episode-num system=\"xmltv_ns\">1.4.</episode-num><episode-num system=\"onscreen\">S02E05</episode-num>" +
                        "<new/><rating system=\"VCHIP\"><value>TV-PG</value></rating></programme>",
                ), xml)
                assertFalse(xml.contains("original-air-date\">2026-10-10 13:00:00"), "a numbered programme keeps the source's numbering only")

                val query = GuideQuery(listOf(hls.id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")
                val window = client.post("/api/v1/guide/query") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(query)) }
                val programme = Json.decodeFromString<GuideWindow>(window.bodyAsText()).channels.single().programmes.single()
                assertEquals("Morning", programme.title)
                assertEquals("Pilot & Plans", programme.subTitle)
                assertEquals("S2 E5", programme.episode)
                assertTrue(programme.isNew)
                assertEquals("TV-PG", programme.rating)
                assertEquals(listOf("Sports"), programme.categories)
                assertNull(programme.icon)
            } finally { served.runtime.close(); served.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `an HLS stream URL redirects players to a shared session that lives while they fetch media`() = testApplication {
        val dir = Files.createTempDirectory("sofa-output-hls-")
        Fixture().use { fixture ->
            val served = serve(fixture, dir, listOf(fixture.feed))
            try {
                val raw = createClient { followRedirects = false }
                val hls = served.channels.single()
                val redirect = raw.get("/output/stream/${hls.id}.m3u8?token=$output")
                assertEquals(HttpStatusCode.Found, redirect.status)
                val location = redirect.headers[HttpHeaders.Location]!!
                assertTrue(location.startsWith("$base/media/sessions/") && location.endsWith(".m3u8"), location)
                val path = location.removePrefix(base)
                // A redirect-following player lands on the rewritten playlist.
                val followed = client.get("/output/stream/${hls.id}.m3u8?token=$output")
                assertEquals(HttpStatusCode.OK, followed.status)
                assertTrue(followed.headers[HttpHeaders.ContentType]!!.contains("mpegurl"))
                assertTrue(followed.bodyAsText().lineSequence().any { it.startsWith("/media/sessions/") })
                // A second player on the same channel shares the session instead of taking a slot.
                assertEquals(location, raw.get("/output/stream/${hls.id}.m3u8?token=$output").headers[HttpHeaders.Location])
                assertEquals(1, served.runtime.sessions.count())
                // Media requests hold the lease; a minute without one releases it.
                served.clock.now += Duration.ofSeconds(50)
                assertEquals(HttpStatusCode.OK, client.get(path).status)
                served.clock.now += Duration.ofSeconds(50)
                served.runtime.sessions.expire()
                assertEquals(HttpStatusCode.OK, client.get(path).status)
                served.clock.now += Duration.ofSeconds(61)
                served.runtime.sessions.expire()
                assertEquals(0, served.runtime.sessions.count())
                assertEquals(HttpStatusCode.Gone, client.get(path).status)
                // The next player opens a fresh session.
                assertNotEquals(location, raw.get("/output/stream/${hls.id}.m3u8?token=$output").headers[HttpHeaders.Location])
            } finally { served.runtime.close(); served.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `at capacity an idle player session yields to the next zap`() = testApplication {
        val dir = Files.createTempDirectory("sofa-output-capacity-")
        Fixture().use { fixture ->
            val served = serve(fixture, dir, listOf(fixture.feed, fixture.tsFeed), maxSessions = 1)
            try {
                val raw = createClient { followRedirects = false }
                val hls = served.channels.single { it.displayNumber == "5" }
                val ts = served.channels.single { it.displayNumber == "6" }
                val idle = raw.get("/output/stream/${hls.id}.m3u8?token=$output").headers[HttpHeaders.Location]!!.removePrefix(base)
                assertEquals(HttpStatusCode.OK, client.get(idle).status)
                val head = withTimeout(20_000) {
                    client.prepareGet("/output/stream/${ts.id}.ts?token=$output").execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        response.bodyAsChannel().readHead(188 * 3)
                    }
                }
                assertEquals(0x47.toByte(), head[0])
                // The zap took the only slot from the idle HLS session.
                assertEquals(HttpStatusCode.Gone, client.get(idle).status)
            } finally { served.runtime.close(); served.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `at capacity the stalest idle player session goes, not the one still fetching`() = testApplication {
        val dir = Files.createTempDirectory("sofa-output-stalest-")
        Fixture().use { fixture ->
            val served = serve(fixture, dir, listOf(fixture.feed, fixture.altFeed, fixture.tsFeed), maxSessions = 2)
            try {
                val raw = createClient { followRedirects = false }
                val (first, second) = served.channels.filter { it.displayNumber == "5" }
                val ts = served.channels.single { it.displayNumber == "6" }
                val older = raw.get("/output/stream/${first.id}.m3u8?token=$output").headers[HttpHeaders.Location]!!.removePrefix(base)
                served.clock.now += Duration.ofSeconds(5)
                val newer = raw.get("/output/stream/${second.id}.m3u8?token=$output").headers[HttpHeaders.Location]!!.removePrefix(base)
                // The older session is the one a player is still watching: its media request is the most recent.
                served.clock.now += Duration.ofSeconds(5)
                assertEquals(HttpStatusCode.OK, client.get(older).status)
                withTimeout(20_000) {
                    client.prepareGet("/output/stream/${ts.id}.ts?token=$output").execute { response -> assertEquals(HttpStatusCode.OK, response.status); response.bodyAsChannel().readHead(188) }
                }
                assertEquals(HttpStatusCode.Gone, client.get(newer).status)
                assertEquals(HttpStatusCode.OK, client.get(older).status)
            } finally { served.runtime.close(); served.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `a TS stream URL whose unprobed source serves HLS switches to the playlist instead of answering 410`() = testApplication {
        val dir = Files.createTempDirectory("sofa-output-mismatch-")
        Fixture().use { fixture ->
            val served = serve(fixture, dir, listOf(fixture.proxyFeed))
            try {
                val raw = createClient { followRedirects = false }
                val channel = served.channels.single()
                assertTrue(client.get("/output/playlist.m3u?token=$output").bodyAsText().contains("/output/stream/${channel.id}.ts?token="))
                val redirect = withTimeout(20_000) { raw.get("/output/stream/${channel.id}.ts?token=$output") }
                assertEquals(HttpStatusCode.Found, redirect.status)
                val location = redirect.headers[HttpHeaders.Location]!!
                assertTrue(Regex("$base/media/sessions/[^/]+/2/[^/]+\\.m3u8").matches(location), location)
                val playlist = client.get(location.removePrefix(base))
                assertEquals(HttpStatusCode.OK, playlist.status)
                assertTrue(playlist.bodyAsText().lines().contains("#EXT-X-MEDIA-SEQUENCE:7"))
                assertEquals(mapOf(served.runtime.feeds.offerings().single().id to "hls"), served.store.offeringProtocols())
                assertTrue(client.get("/output/playlist.m3u?token=$output").bodyAsText().contains("/output/stream/${channel.id}.m3u8?token="))
            } finally { served.runtime.close(); served.store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `documents compress and media, streams and logos never do`() {
        for (path in listOf("/api/v1/channels", "/admin/lineup", "/output/guide.xml", "/output/playlist.m3u", "/lineup.json")) assertTrue(compressible(routedPath(path)), path)
        for (path in listOf("/media/sessions/a/1/seg.ts", "/auto/v5", "/output/stream/x.ts", "/logos/abc", "/output/stream/x.m3u8", "/healthz")) assertFalse(compressible(routedPath(path)), path)
        assertFalse(compressible(null))
    }

    @Test fun `a station's group gives its programmes the kind Plex sorts by, then the group itself`() {
        assertEquals(listOf("Movie", "Movies"), xmltvCategories("Movies"))
        assertEquals(listOf("Sports"), xmltvCategories("Sports"))
        assertEquals(listOf("News", "Local News (US)"), xmltvCategories("Local News (US)"))
        assertEquals(listOf("Children", "Kids & Family"), xmltvCategories("Kids & Family"))
        assertEquals(listOf("Series", "Drama"), xmltvCategories("Drama"))
        assertEquals(listOf("Series"), xmltvCategories(null))
    }
}
