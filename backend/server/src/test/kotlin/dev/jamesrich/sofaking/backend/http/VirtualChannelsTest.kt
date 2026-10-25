package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.VirtualAiring
import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.api.VirtualSlot
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.pollFor
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import dev.jamesrich.sofaking.backend.virtual.SourceKind
import dev.jamesrich.sofaking.backend.virtual.VirtualStreams
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Virtual channels end to end: a library clip scheduled, listed as a source, guided and streamed through FFmpeg. */
class VirtualChannelsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val admin = "admin".repeat(8)
    private val local = HttpClient.newHttpClient()

    private class Virtual(
        val app: ApplicationTestBuilder,
        val runtime: LiveRuntime,
        val library: LibraryRecord,
        val dir: Path,
    )

    /** What the streams' FFmpeg said, for a failure's message. */
    private fun logs(dir: Path): String = Files.walk(dir.resolve("virtual")).use { paths ->
        paths.filter { it.fileName.toString() == "ffmpeg.log" || it.fileName.toString() == "piece.m3u8" }.toList()
            .joinToString("\n") { "$it: ${Files.readString(it)}" }
    }

    @Test fun `a channel schedules the library's clip and lists it in the guide`() = server {
        val channel = create(VirtualChannel(name = "Clips", number = "2001", slots = listOf(slot(library.id))))
        assertEquals("2001", channel.number)
        val airings = json.decodeFromString<List<VirtualAiring>>(
            app.client.get("/api/v1/admin/virtual-channels/${channel.id}/schedule?hours=1") { bearerAuth(admin) }
                .bodyAsText(),
        )
        assertTrue(airings.isNotEmpty())
        assertTrue(airings.all { it.title == "Clip" }, airings.toString())
        assertTrue(airings.zipWithNext().all { (a, b) -> a.endsAt == b.startsAt }, "the schedule has no gaps")
        val programs = runtime.virtual!!.programs(setOf(channel.id))
        assertEquals("Clip", programs.first().title)
        val listed = pollFor(10_000) { runtime.feeds.published.channels("virtual").firstOrNull { it.name == "Clips" } }
        assertEquals("2001", listed.displayNumber)
    }

    @Test fun `a bad channel is refused and a deleted one is gone`() = server {
        val mixed = VirtualChannel(
            name = "Mixed",
            slots = listOf(slot(library.id).copy(start = "18:00"), slot(library.id)),
        )
        val refused = app.client.post("/api/v1/admin/virtual-channels") { body(mixed) }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("start time" in refused.bodyAsText())
        val unknown = VirtualChannel(name = "Nothing", slots = listOf(slot("no-such-library")))
        assertEquals(
            HttpStatusCode.BadRequest,
            app.client.post("/api/v1/admin/virtual-channels") {
                body(unknown)
            }.status,
        )
        val channel = create(VirtualChannel(name = "Brief", slots = listOf(slot(library.id))))
        val deleted = app.client.delete("/api/v1/admin/virtual-channels/${channel.id}") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        val gone = app.client.get("/api/v1/admin/virtual-channels/${channel.id}") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.NotFound, gone.status)
    }

    @Test fun `the channel streams as live HLS on the loopback route the relay may fetch`() = server {
        val channel = create(VirtualChannel(name = "Clips", slots = listOf(slot(library.id))))
        val offering = pollFor(10_000) {
            runtime.feeds.published.channels("virtual").firstOrNull()?.let {
                runtime.feeds.published.offeringsOf(it.id).firstOrNull()
            }
        }
        val stream = runtime.feeds.playable(offering)
        assertTrue(stream.url.startsWith("http://127.0.0.1:"), "the source is the server's own route")
        runtime.feeds.upstream.check(URI(stream.url))
        assertFailsWith<UpstreamRejectedException>("other loopback addresses stay refused") {
            runtime.feeds.upstream.check(URI("http://127.0.0.1:9/elsewhere"))
        }
        val playlist = fetch(stream.url)
        assertEquals(200, playlist.statusCode())
        val text = playlist.body().decodeToString()
        assertTrue(text.startsWith("#EXTM3U"), text)
        assertTrue("#EXT-X-DISCONTINUITY" in text, "one clip gives way to the next: $text")
        val segments = text.lines().filter { it.endsWith(".ts") }
        assertTrue(segments.size >= 3, text + logs(dir))
        val segment = fetch(URI(stream.url).resolve(segments.first()).toString())
        assertEquals(200, segment.statusCode())
        assertEquals(0x47, segment.body().first().toInt(), "an MPEG-TS segment")
        val forged = stream.url.replace(Regex("/local/[^/]+/"), "/local/wrong/")
        assertEquals(401, fetch(forged).statusCode())
        assertEquals(1, runtime.virtualStreams!!.running)
        runtime.virtual!!.delete(channel.id)
        assertEquals(404, fetch(stream.url).statusCode())
    }

    @Test fun `the portal adds a channel from the form and shows what it airs`() = server {
        val browser = app.createClient {
            install(HttpCookies)
            followRedirects = false
        }
        browser.submitForm("/admin/login", parameters { append("token", admin) })
        val page = browser.get("/admin/virtual").bodyAsText()
        assertTrue("Turn on Virtual channels" !in page, "the source is configured")
        assertTrue("Library: Movies" in page, page)
        val csrf = Regex("""name="csrf" value="([^"]*)"""").find(page)!!.groupValues[1]
        val saved = browser.submitForm(
            "/admin/virtual",
            parameters {
                append("csrf", csrf)
                append("name", "Movie night")
                append("source.0", "${SourceKind.LIBRARY}:${library.id}")
                append("shuffle.0", "true")
                append("padMinutes", "0")
            },
        )
        assertEquals(HttpStatusCode.SeeOther, saved.status)
        val edited = browser.get(saved.headers["Location"]!!).bodyAsText()
        assertTrue("Saved Movie night." in edited, edited)
        assertTrue("Coming up" in edited && "Clip" in edited, edited)
        assertEquals(true, runtime.virtual!!.channels().single().slots.single().shuffle)
    }

    @Test fun `the live playlist marks each new piece and numbers its segments on`() {
        val at = java.time.Instant.parse("2026-10-25T18:00:00Z")
        val text = VirtualStreams.playlistText(
            listOf(
                VirtualStreams.ListedSegment(7, 2, 1, at, 4.0),
                VirtualStreams.ListedSegment(8, 3, 0, at.plusSeconds(4), 2.5),
            ),
        )
        assertTrue("#EXT-X-MEDIA-SEQUENCE:7\n" in text)
        assertTrue("#EXT-X-DISCONTINUITY-SEQUENCE:2\n" in text)
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY\n").findAll(text).count())
        assertTrue(text.trimEnd().endsWith("#EXTINF:2.500,\n3-0.ts"), text)
    }

    private fun slot(library: String) = VirtualSlot(VirtualSource(SourceKind.LIBRARY, library))

    private fun io.ktor.client.request.HttpRequestBuilder.body(channel: VirtualChannel) {
        bearerAuth(admin)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(channel))
    }

    private suspend fun Virtual.create(channel: VirtualChannel): VirtualChannel {
        val response: HttpResponse = app.client.post("/api/v1/admin/virtual-channels") { body(channel) }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun fetch(url: String): java.net.http.HttpResponse<ByteArray> = withContext(Dispatchers.IO) {
        local.send(HttpRequest.newBuilder(URI(url)).build(), BodyHandlers.ofByteArray())
    }

    /** An eight-second H.264 and AAC clip, or MPEG-2 video where FFmpeg has no libx264. */
    private fun clip(file: Path) {
        Files.createDirectories(file.parent)
        fun run(codec: List<String>): Boolean {
            val command = listOf(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000", "-t", "8", "-pix_fmt", "yuv420p", "-g", "15",
            ) + codec + listOf("-c:a", "aac", file.toString())
            val process =
                ProcessBuilder(
                    command,
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0
        }
        check(run(listOf("-c:v", "libx264", "-preset", "ultrafast")) || run(listOf("-c:v", "mpeg2video"))) {
            "FFmpeg could not write the clip"
        }
    }

    /** A server with a `virtual` source and the clip as a movie library, scanned. */
    private fun server(block: suspend Virtual.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-virtual-")
        clip(dir.resolve("media/movies/Clip (2020)/Clip (2020).mkv"))
        Files.writeString(
            dir.resolve("sources.json"),
            """{"providers":[{"id":"virtual","type":"native","service":"virtual"}]}""",
        )
        val config = ServerConfig(
            "127.0.0.1",
            8080,
            dir,
            dir.resolve("sources.json"),
            token,
            adminToken = admin,
            hdhr = HdhrConfig(enabled = false),
            updateImage = null,
        )
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        try {
            val library = runtime.libraries!!.create(
                "Movies",
                LibraryKind.MOVIES,
                dir.resolve("media/movies").toString(),
            )
            runtime.libraries!!.scan(library.id)
            application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
            Virtual(this, runtime, library, dir).block()
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }
}
