package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.PgsFixture
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.UpdateVodPlayback
import dev.jamesrich.sofaking.contract.VodSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.append
import io.ktor.http.contentType
import io.ktor.http.expires
import io.ktor.http.parameters
import io.ktor.http.plus
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VodApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val admin = "admin".repeat(8)

    /** An eight-second H.264 and AAC Matroska file, or MPEG-2 video where FFmpeg has no libx264. */
    private fun clip(file: Path) {
        Files.createDirectories(file.parent)
        fun run(codec: List<String>): Boolean {
            val command = listOf(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000",
                "-t", "8", "-pix_fmt", "yuv420p", "-g", "15",
            ) + codec + listOf("-c:a", "aac", file.toString())
            val process = ProcessBuilder(
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

    /** Muxes [inputs] into [movie] with FFmpeg's `-map` and codec [options]. */
    private fun mux(movie: Path, inputs: List<Path>, options: List<String>) {
        Files.createDirectories(movie.parent)
        val command = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y") +
            inputs.flatMap { listOf("-i", it.toString()) } + options + movie.toString()
        val process =
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "FFmpeg muxed the subtitles")
    }

    @Test fun `a client that plays the container and codecs gets the file itself, with byte ranges`() = clipServer {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/vod-sessions").status)
        val direct = session(open(ClientProfile(listOf("mkv"), listOf(video()), listOf("aac"))))
        assertEquals(Wire.VodMode.DIRECT, direct.mode)
        assertEquals("file", direct.descriptor.protocol)
        assertEquals("video/x-matroska", direct.descriptor.mimeType)
        val part = client.get(direct.descriptor.url) { header(HttpHeaders.Range, "bytes=0-99") }
        assertEquals(HttpStatusCode.PartialContent, part.status, "media takes no token; ranges answer 206")
        assertEquals(100, part.readRawBytes().size)
        val other = direct.descriptor.url.replaceAfterLast('/', "other.mkv")
        assertEquals(HttpStatusCode.NotFound, client.get(other).status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get(direct.descriptor.url.replace("/1/", "/2/")).status,
            "a stale epoch is gone",
        )
    }

    @Test fun `a client that takes the codecs but not the container gets them copied into HLS`() = clipServer {
        // From where it asked to start.
        val remuxed = open(ClientProfile(listOf("mp4"), listOf(video()), listOf("aac")), startMs = 2000)
        assertEquals(HttpStatusCode.Created, remuxed.status)
        val remuxBody = remuxed.bodyAsText()
        assertKeepsWireFixture("v1/vod-session.json", remuxBody)
        val remux = json.decodeFromString<VodSession>(remuxBody)
        assertEquals(Wire.VodMode.REMUX, remux.mode)
        assertEquals(2000, remux.startMs)
        assertEquals("hls", remux.descriptor.protocol)
        val playlist = client.get(remux.descriptor.url).bodyAsText()
        assertTrue("seg0.ts" in playlist, playlist)
        val segment = client.get(remux.descriptor.url.replaceAfterLast('/', "seg0.ts"))
        assertEquals(HttpStatusCode.OK, segment.status)
        assertEquals(ContentType.parse("video/mp2t"), segment.contentType())
    }

    @Test fun `only one file converts at a time, and direct play does not count`() = clipServer {
        assertEquals(
            Wire.VodMode.DIRECT,
            session(open(ClientProfile(listOf("mkv"), listOf(video()), listOf("aac")))).mode,
        )
        assertEquals(HttpStatusCode.Created, open(remuxProfile()).status)
        val busy = open(ClientProfile())
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertEquals(Wire.ErrorCode.CAPACITY_EXHAUSTED, json.decodeFromString<ApiError>(busy.bodyAsText()).code)
    }

    @Test fun `a preview lasts 45 seconds and a watch five minutes without a renewal`() = clipServer {
        val direct = ClientProfile(listOf("mkv"), listOf(video()), listOf("aac"))
        val opened = open(direct, intent = Wire.Intent.PREVIEW).bodyAsText()
        assertKeepsWireFixture("v1/vod-preview-session.json", opened)
        val preview = json.decodeFromString<VodSession>(opened)
        val watch = session(open(direct))
        assertEquals(Wire.Intent.PREVIEW, preview.intent)
        assertEquals(Wire.Intent.WATCH, watch.intent)
        clock.now = clock.now.plus(Duration.ofSeconds(46))
        runtime.vod!!.expire()
        assertEquals(HttpStatusCode.Gone, client.get("/api/v1/vod-sessions/${preview.id}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/vod-sessions/${watch.id}") { bearerAuth(token) }.status)
    }

    @Test fun `renewing a preview inside its lease keeps it past the first lease`() = clipServer {
        val direct = ClientProfile(listOf("mkv"), listOf(video()), listOf("aac"))
        val preview = session(open(direct, intent = Wire.Intent.PREVIEW))
        repeat(3) {
            clock.now = clock.now.plus(Duration.ofSeconds(15))
            val renewed = client.patch("/api/v1/vod-sessions/${preview.id}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, renewed.status)
            assertEquals(Wire.Intent.PREVIEW, session(renewed).intent)
        }
        runtime.vod!!.expire()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/vod-sessions/${preview.id}") { bearerAuth(token) }.status)
        clock.now = clock.now.plus(Duration.ofSeconds(46))
        runtime.vod!!.expire()
        assertEquals(HttpStatusCode.Gone, client.get("/api/v1/vod-sessions/${preview.id}") { bearerAuth(token) }.status)
    }

    @Test fun `a watch open ends a preview that holds the only conversion slot`() = clipServer {
        val preview = session(open(remuxProfile(), intent = Wire.Intent.PREVIEW))
        assertEquals(Wire.VodMode.REMUX, preview.mode)
        val watch = open(ClientProfile())
        assertEquals(HttpStatusCode.Created, watch.status, "the preview yields its slot")
        assertEquals(Wire.Intent.WATCH, session(watch).intent)
        val gone = client.get("/api/v1/vod-sessions/${preview.id}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Gone, gone.status, "the evicted preview answers its next renewal as gone")
        pollUntil(10_000) { transcoding() == 1L }
    }

    @Test fun `a preview never ends a watch and answers busy when no slot is free`() = clipServer {
        val watch = session(open(remuxProfile()))
        val preview = open(remuxProfile(), intent = Wire.Intent.PREVIEW)
        assertEquals(HttpStatusCode.ServiceUnavailable, preview.status)
        assertEquals(Wire.ErrorCode.CAPACITY_EXHAUSTED, json.decodeFromString<ApiError>(preview.bodyAsText()).code)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/vod-sessions/${watch.id}") { bearerAuth(token) }.status)
    }

    @Test fun `a preview that plays the file as it is holds no conversion slot`() = clipServer {
        val direct = ClientProfile(listOf("mkv"), listOf(video()), listOf("aac"))
        val preview = session(open(direct, intent = Wire.Intent.PREVIEW))
        assertEquals(Wire.VodMode.DIRECT, preview.mode)
        assertEquals(HttpStatusCode.Created, open(remuxProfile()).status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/vod-sessions/${preview.id}") { bearerAuth(token) }.status)
    }

    @Test fun `an intent the server does not know answers 400`() = clipServer {
        val response = client.post("/api/v1/vod-sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"${item().id}","intent":"binge"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test fun `a seek past what is written restarts FFmpeg there under a new epoch`() = clipServer {
        val remux = session(open(remuxProfile(), startMs = 2000))
        val restarted = json.decodeFromString<VodSession>(
            client.patch("/api/v1/vod-sessions/${remux.id}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"startMs":4000}""")
            }.bodyAsText(),
        )
        assertEquals(2, restarted.descriptor.epoch)
        assertEquals(4000, restarted.startMs)
        assertEquals(HttpStatusCode.NotFound, client.get(remux.descriptor.url).status, "the old URLs stop answering")
        assertTrue("seg0.ts" in client.get(restarted.descriptor.url).bodyAsText())
    }

    @Test fun `a released session frees its slot for a client that plays nothing listed`() = clipServer {
        val remux = session(open(remuxProfile()))
        val released = client.delete("/api/v1/vod-sessions/${remux.id}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, released.status)
        assertEquals(HttpStatusCode.Gone, client.get("/api/v1/vod-sessions/${remux.id}") { bearerAuth(token) }.status)
        val converted = session(open(ClientProfile()))
        assertEquals(Wire.VodMode.TRANSCODE, converted.mode)
        assertTrue("seg0.ts" in client.get(converted.descriptor.url).bodyAsText())
    }

    @Test fun `an unrenewed session expires and leaves no files and no FFmpeg`() = clipServer {
        val converted = session(open(ClientProfile()))
        assertTrue("seg0.ts" in client.get(converted.descriptor.url).bodyAsText())
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        runtime.vod!!.expire()
        val expired = client.get("/api/v1/vod-sessions/${converted.id}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Gone, expired.status)
        assertFalse(Files.exists(dir.resolve("transcode/${converted.id}")), "its files are deleted")
        assertEquals(0, transcoding(), "no FFmpeg outlives its session")
    }

    @Test fun `a session for an unknown item answers 404`() = clipServer {
        val response = client.post("/api/v1/vod-sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"itemId":"nope"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test fun `a session released or expired while a seek restarts it leaves no FFmpeg and no files behind`() =
        runBlocking<Unit> {
            val dir = Files.createTempDirectory("sofa-vod-restart-")
            clip(dir.resolve("media/movies/Clip (2020)/Clip (2020).mkv"))
            Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    dir.resolve("sources.json"),
                    token,
                    hdhr = HdhrConfig(enabled = false),
                    updateImage = null,
                    maxTranscodes = 1,
                )
            val store = openStore(dir)
            val clock = MutableClock()
            val runtime = LiveRuntime.create(store, config, clock, probes = false)
            try {
                val library = runtime.libraries!!.create(
                    "Movies",
                    LibraryKind.MOVIES,
                    dir.resolve("media/movies").toString(),
                )
                runtime.libraries!!.scan(library.id)
                val movie = runtime.libraries!!.catalog.items(library.id, null, false, null, 10).items.single()
                val vod = runtime.vod!!
                // The release lands before, during and after the restart's stop and start, and an expiry once.
                for (wait in listOf(0L, 5L, 20L, 60L, 200L, -1L)) {
                    val session = vod.open(OpenVodPlayback(movie.id, ClientProfile()))
                    coroutineScope {
                        launch(Dispatchers.Default) {
                            runCatching { vod.update(session.id, UpdateVodPlayback(startMs = 4000)) }
                        }
                        launch(Dispatchers.Default) {
                            delay(wait.coerceAtLeast(0))
                            if (wait >= 0) {
                                vod.release(session.id)
                            } else {
                                clock.now = clock.now.plus(Duration.ofMinutes(6))
                                vod.expire()
                            }
                        }
                    }
                    assertTrue(vod.snapshot().isEmpty(), "wait $wait")
                }
                fun transcoding() = ProcessHandle.current().children().filter { child ->
                    child.info().arguments().orElse(emptyArray()).any { it.startsWith(dir.toString()) }
                }.count()
                pollUntil(10_000) { transcoding() == 0L }
                assertTrue(
                    Files.list(dir.resolve("transcode")).use { files ->
                        files.noneMatch { Files.isDirectory(it) && it.fileName.toString() != "subtitles" }
                    },
                    "every session's folder is deleted",
                )
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `text subtitles come as WebVTT, from a kept copy after the first read`() = subtitled {
        val item = item()
        val subs = client.get("/api/v1/library/items/${item.id}/subtitles/3") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, subs.status)
        assertEquals(ContentType.parse("text/vtt"), subs.contentType()?.withoutParameters())
        val vtt = subs.bodyAsText()
        assertTrue(vtt.startsWith("WEBVTT"), vtt)
        assertTrue("00:01.000 --> 00:03.000" in vtt && "Hello there" in vtt, vtt)
        assertEquals(
            vtt,
            client.get("/api/v1/library/items/${item.id}/subtitles/3") { bearerAuth(token) }.bodyAsText(),
            "the second read is the kept copy",
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/library/items/${item.id}/subtitles/1") { bearerAuth(token) }.status,
            "stream 1 is audio",
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/library/items/${item.id}/subtitles/3").status)
    }

    @Test fun `a picked audio stream other than the default is never played directly`() = subtitled {
        val detail = catalog.item(item().id)
        assertEquals(listOf(3), detail.subtitles.map { it.index })
        assertEquals(listOf(1, 2), detail.audio.map { it.index })
        val profile = ClientProfile(listOf("mkv"), listOf(detail.videoCodec!!), listOf("aac"))
        assertEquals(Wire.VodMode.DIRECT, session(open(profile, audioIndex = 1)).mode, "the default plays directly")
        assertEquals(Wire.VodMode.REMUX, session(open(profile, audioIndex = 2)).mode, "another is carried in HLS")
    }

    @Test fun `subtitle files beside a video are listed with their language and forced flag`() = sidecars {
        val detail = catalog.item(item().id)
        assertEquals(
            listOf(2 to "hdmv_pgs_subtitle", 1000 to "hdmv_pgs_subtitle", 1001 to "subrip"),
            detail.subtitles.map { it.index to it.codec },
        )
        assertEquals("de", detail.subtitles[1].language)
        assertTrue(detail.subtitles[2].forced)
    }

    @Test fun `a text file beside a video comes as WebVTT and picture subtitles answer 415`() = sidecars {
        val item = item()
        suspend fun subtitle(index: Int) =
            client.get("/api/v1/library/items/${item.id}/subtitles/$index") { bearerAuth(token) }
        val vtt = subtitle(1001).bodyAsText()
        assertTrue(vtt.startsWith("WEBVTT") && "Hello there" in vtt, vtt)
        assertEquals(HttpStatusCode.UnsupportedMediaType, subtitle(2).status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, subtitle(1000).status)
        assertEquals(HttpStatusCode.NotFound, subtitle(1002).status)
    }

    @Test fun `a picture subtitle is drawn into an encoded picture and text is left to the player`() = sidecars {
        val profile = ClientProfile(listOf("mkv"), listOf(video()), listOf("aac"))
        for (index in listOf(2, 1000)) {
            val session = session(open(profile, startMs = 1_500, subtitleIndex = index))
            assertEquals(Wire.VodMode.TRANSCODE, session.mode, "subtitle $index is drawn, so the picture is encoded")
            assertTrue("seg0.ts" in client.get(session.descriptor.url).bodyAsText(), "FFmpeg drew subtitle $index")
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/v1/vod-sessions/${session.id}") { bearerAuth(token) }.status,
            )
        }
        val text = open(profile, startMs = 1_500, subtitleIndex = 1001)
        assertEquals(HttpStatusCode.BadRequest, text.status, "text is drawn by the player")
        assertEquals(HttpStatusCode.NotFound, open(profile, startMs = 1_500, subtitleIndex = 7).status)
    }

    @Test fun `an episode with no picture of its own shows a frame as its poster and backdrop`() = pilotServer {
        assertEquals(
            listOf("Media", "Recordings"),
            runtime.libraries!!.roots.map { it.label },
            "recordings can be a library too",
        )
        val show = item()
        val pilot = pilot()
        val still = client.get(pilot.poster!!)
        assertEquals(HttpStatusCode.OK, still.status)
        assertEquals(ContentType.Image.JPEG, still.contentType())
        val bytes = still.readRawBytes()
        assertTrue(bytes.size > 100 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(), "a JPEG of the frame")
        assertEquals(
            pilot.poster!!.substringBefore('?').replace("poster", "backdrop"),
            pilot.backdrop!!.substringBefore('?'),
            "the frame is its backdrop too",
        )
        assertNull(show.poster, "a show has no frame of its own")
    }

    @Test fun `the portal lists a library session as watching now, and Stop ends it`() = pilotServer {
        open(ClientProfile(), itemId = pilot().id)
        val browser = app.createClient {
            install(HttpCookies)
            followRedirects = false
        }
        browser.submitForm("/admin/login", parameters { append("token", admin) })
        val home = browser.get("/admin").bodyAsText()
        assertTrue("Show · S1E1 · Pilot" in home && "transcode" in home, "Watching now lists the converting episode")
        val id = runtime.vod!!.snapshot().single().id
        val csrf = Regex("""name="csrf" value="([^"]*)"""").find(home)!!.groupValues[1]
        assertEquals(
            HttpStatusCode.SeeOther,
            browser.submitForm("/admin/vod/$id/stop", parameters { append("csrf", csrf) }).status,
        )
        assertTrue(runtime.vod!!.snapshot().isEmpty(), "Stop ends it")
    }

    @Test fun `a whole playlist lists the item and converts from the segment a player asks for`() = longServer {
        val session = session(open(ClientProfile(), startMs = 4_500, wholePlaylist = true))
        assertEquals(Wire.VodMode.TRANSCODE, session.mode)
        assertEquals(0, session.startMs, "the playlist starts at the item's start")
        val base = session.descriptor.url.substringBeforeLast('/')
        val playlist = client.get(session.descriptor.url).bodyAsText()
        assertEquals(listOf("seg0.ts", "seg1.ts"), playlist.lines().filter { it.endsWith(".ts") }, playlist)
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
        val second = client.get("$base/seg1.ts")
        assertEquals(HttpStatusCode.OK, second.status, "FFmpeg started at the segment holding startMs")
        assertTrue(second.readRawBytes().isNotEmpty())
        assertEquals(HttpStatusCode.OK, client.get("$base/seg0.ts").status, "a seek back converts from there")
        assertEquals(HttpStatusCode.OK, client.get("$base/seg1.ts").status, "and a seek forward plays what is kept")
        assertEquals(HttpStatusCode.NotFound, client.get("$base/seg2.ts").status, "past the end")
    }

    @Test fun `a restart only renews a whole playlist, and a copy starts where asked`() = longServer {
        val session = session(open(ClientProfile(), startMs = 4_500, wholePlaylist = true))
        val renewed = json.decodeFromString<VodSession>(
            client.patch("/api/v1/vod-sessions/${session.id}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"startMs":6000}""")
            }.bodyAsText(),
        )
        assertEquals(session.descriptor.url, renewed.descriptor.url, "a restart only renews a whole playlist")
        client.delete("/api/v1/vod-sessions/${session.id}") { bearerAuth(token) }
        val copied = session(open(remuxProfile(), startMs = 4_500, wholePlaylist = true))
        assertEquals(4_500, copied.startMs, "a copy keeps the playlist that starts where asked")
    }

    /** A scanned library and the server playing from it. */
    private inner class Vod(
        val app: ApplicationTestBuilder,
        val dir: Path,
        val runtime: LiveRuntime,
        val clock: MutableClock,
        private val library: LibraryRecord,
    ) {
        val client get() = app.client
        val catalog get() = runtime.libraries!!.catalog

        /** The library's one top-level item. */
        suspend fun item() = catalog.items(library.id, null, false, null, 10).items.single()

        /** The first episode of the one show. */
        suspend fun pilot() = catalog.item(catalog.item(item().id).children.single().id).children.single()

        /** The item's video codec, as FFprobe read it. */
        suspend fun video(): String = assertNotNull(catalog.item(item().id).videoCodec, "FFprobe read the clip")

        /** A client that takes the item's codecs but not Matroska. */
        suspend fun remuxProfile() = ClientProfile(listOf("mp4"), listOf(video()), listOf("aac"))

        suspend fun open(
            profile: ClientProfile,
            startMs: Long = 0,
            audioIndex: Int? = null,
            subtitleIndex: Int? = null,
            wholePlaylist: Boolean = false,
            itemId: String? = null,
            intent: String = Wire.Intent.WATCH,
        ): HttpResponse {
            val request = OpenVodPlayback(
                itemId ?: item().id,
                profile,
                startMs,
                audioIndex = audioIndex,
                subtitleIndex = subtitleIndex,
                wholePlaylist = wholePlaylist,
                intent = intent,
            )
            return client.post("/api/v1/vod-sessions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(OpenVodPlayback.serializer(), request))
            }
        }

        suspend fun session(response: HttpResponse): VodSession = json.decodeFromString(response.bodyAsText())

        /** The FFmpeg processes working on this test's files. */
        fun transcoding() = ProcessHandle.current().children().filter { child ->
            child.info().arguments().orElse(emptyArray()).any { it.startsWith(dir.toString()) }
        }.count()
    }

    /**
     * Runs [block] against a server whose [folder] under `media`, made by [media], is a [kind] library, scanned, with
     * room to convert [maxTranscodes] files at once.
     */
    private fun vodServer(
        prefix: String,
        media: (Path) -> Unit,
        folder: String = "movies",
        kind: LibraryKind = LibraryKind.MOVIES,
        maxTranscodes: Int = 1,
        block: suspend Vod.() -> Unit,
    ) = testApplication {
        val dir = Files.createTempDirectory(prefix)
        media(dir)
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config =
            ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                dir.resolve("sources.json"),
                token,
                adminToken = admin,
                hdhr = HdhrConfig(enabled = false),
                updateImage = null,
                maxTranscodes = maxTranscodes,
            )
        val store = openStore(dir)
        val clock = MutableClock()
        val runtime = LiveRuntime.create(store, config, clock, probes = false)
        try {
            val name = if (kind == LibraryKind.SHOWS) "TV" else "Movies"
            val library = runtime.libraries!!.create(name, kind, dir.resolve("media/$folder").toString())
            runtime.libraries!!.scan(library.id)
            application { backendModule(store, clock, runtime, token, admin, config = config) }
            Vod(this, dir, runtime, clock, library).block()
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    /** The eight-second clip as a movie. */
    private fun clipServer(block: suspend Vod.() -> Unit) =
        vodServer("sofa-vod-", { clip(it.resolve("media/movies/Clip (2020)/Clip (2020).mkv")) }, block = block)

    /** The clip with its audio twice and SubRip subtitles: streams 0, 1, 2 and 3. */
    private fun subtitled(block: suspend Vod.() -> Unit) = vodServer("sofa-vod-subs-", { dir ->
        val source = dir.resolve("plain.mkv")
        clip(source)
        val srt = dir.resolve("subs.srt")
        Files.writeString(
            srt,
            "1\n00:00:01,000 --> 00:00:03,000\nHello there\n\n2\n00:00:04,000 --> 00:00:06,500\n<i>Second</i> line\n",
        )
        mux(
            dir.resolve("media/movies/Talk (2021)/Talk (2021).mkv"),
            listOf(source, srt),
            listOf("-map", "0:v", "-map", "0:a", "-map", "0:a", "-map", "1:s", "-c", "copy", "-c:s", "srt"),
        )
    }, block = block)

    /**
     * The clip with PGS subtitles as stream 2, and beside it a forced English SubRip file and a German PGS one, with
     * room to convert two files at once.
     */
    private fun sidecars(block: suspend Vod.() -> Unit) = vodServer("sofa-vod-sidecars-", { dir ->
        val source = dir.resolve("plain.mkv")
        clip(source)
        val sup = dir.resolve("embedded.sup")
        PgsFixture.write(sup, listOf(1_000L..3_000L, 4_000L..6_500L), 320, 180)
        val movie = dir.resolve("media/movies/Talk (2021)/Talk (2021).mkv")
        mux(movie, listOf(source, sup), listOf("-map", "0:v", "-map", "0:a", "-map", "1:s", "-c", "copy"))
        val forced = movie.resolveSibling("Talk (2021).en.forced.srt")
        Files.writeString(forced, "1\n00:00:01,000 --> 00:00:03,000\nHello there\n")
        PgsFixture.write(movie.resolveSibling("Talk (2021).de.sup"), listOf(2_000L..5_000L), 320, 180)
    }, maxTranscodes = 2, block = block)

    /** The clip as the pilot of a show with no artwork. */
    private fun pilotServer(block: suspend Vod.() -> Unit) = vodServer(
        "sofa-vod-portal-",
        { clip(it.resolve("media/tv/Show (2020)/Season 01/Show - S01E01 - Pilot.mkv")) },
        folder = "tv",
        kind = LibraryKind.SHOWS,
        maxTranscodes = 2,
        block = block,
    )

    /** The clip as a movie for whole-playlist tests. */
    private fun longServer(block: suspend Vod.() -> Unit) = vodServer(
        "sofa-vod-whole-",
        { clip(it.resolve("media/movies/Long (2020)/Long (2020).mkv")) },
        maxTranscodes = 2,
        block = block,
    )
}
