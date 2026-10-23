package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.library.LibraryKind
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

    /** An eight-second H.264 and AAC Matroska file, or MPEG-2 video where FFmpeg has no libx264. */
    private fun clip(file: Path) {
        Files.createDirectories(file.parent)
        fun run(codec: List<String>): Boolean {
            val command = listOf(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
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

    @Test fun `a library file plays directly with ranges, or as HLS that restarts where asked`() = testApplication {
        val dir = Files.createTempDirectory("sofa-vod-")
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
            val video = assertNotNull(runtime.libraries!!.catalog.item(movie.id).videoCodec, "FFprobe read the clip")
            application { backendModule(store, clock, runtime, token, config = config) }
            suspend fun open(profile: ClientProfile, startMs: Long = 0) = client.post("/api/v1/vod-sessions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(OpenVodPlayback.serializer(), OpenVodPlayback(movie.id, profile, startMs)))
            }

            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/vod-sessions").status)

            // A client that plays Matroska with these codecs gets the file itself, and seeks with byte ranges.
            val direct = json.decodeFromString<VodSession>(
                open(ClientProfile(listOf("mkv"), listOf(video), listOf("aac"))).bodyAsText(),
            )
            assertEquals(Wire.VodMode.DIRECT, direct.mode)
            assertEquals("file", direct.descriptor.protocol)
            assertEquals("video/x-matroska", direct.descriptor.mimeType)
            val part = client.get(direct.descriptor.url) { header(HttpHeaders.Range, "bytes=0-99") }
            assertEquals(HttpStatusCode.PartialContent, part.status, "media takes no token; ranges answer 206")
            assertEquals(100, part.readRawBytes().size)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(direct.descriptor.url.replaceAfterLast('/', "other.mkv")).status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(direct.descriptor.url.replace("/1/", "/2/")).status,
                "a stale epoch is gone",
            )

            // One that takes the codecs but not the container gets them copied into HLS, from where it asked to start.
            val remuxed = open(ClientProfile(listOf("mp4"), listOf(video), listOf("aac")), startMs = 2000)
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

            // Only one file converts at a time here; direct play is not counted.
            val busy = open(ClientProfile())
            assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
            assertEquals(Wire.ErrorCode.CAPACITY_EXHAUSTED, json.decodeFromString<ApiError>(busy.bodyAsText()).code)

            // A seek past what is written restarts FFmpeg there under a new epoch; the old URLs stop answering.
            val restarted = json.decodeFromString<VodSession>(
                client.patch("/api/v1/vod-sessions/${remux.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"startMs":4000}""")
                }.bodyAsText(),
            )
            assertEquals(2, restarted.descriptor.epoch)
            assertEquals(4000, restarted.startMs)
            assertEquals(HttpStatusCode.NotFound, client.get(remux.descriptor.url).status)
            assertTrue("seg0.ts" in client.get(restarted.descriptor.url).bodyAsText())

            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/v1/vod-sessions/${remux.id}") {
                    bearerAuth(token)
                }.status,
            )
            assertEquals(
                HttpStatusCode.Gone,
                client.get("/api/v1/vod-sessions/${remux.id}") {
                    bearerAuth(token)
                }.status,
            )

            // With the slot free, a client that plays nothing listed gets the file converted.
            val converted = json.decodeFromString<VodSession>(open(ClientProfile()).bodyAsText())
            assertEquals(Wire.VodMode.TRANSCODE, converted.mode)
            assertTrue("seg0.ts" in client.get(converted.descriptor.url).bodyAsText())

            // An unrenewed session expires and frees its slot.
            clock.now = clock.now.plus(Duration.ofMinutes(6))
            runtime.vod!!.expire()
            assertEquals(
                HttpStatusCode.Gone,
                client.get("/api/v1/vod-sessions/${converted.id}") {
                    bearerAuth(token)
                }.status,
            )
            assertFalse(Files.exists(dir.resolve("transcode/${converted.id}")), "its files are deleted")
            val transcoding = ProcessHandle.current().children().filter { child ->
                child.info().arguments().orElse(emptyArray()).any { it.startsWith(dir.toString()) }
            }.count()
            assertEquals(0, transcoding, "no FFmpeg outlives its session")
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/api/v1/vod-sessions") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"itemId":"nope"}""")
                }.status,
            )
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
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

    @Test fun `text subtitles come as WebVTT and a picked audio stream is never played directly`() = testApplication {
        val dir = Files.createTempDirectory("sofa-vod-subs-")
        val source = dir.resolve("plain.mkv")
        clip(source)
        val srt = dir.resolve("subs.srt")
        Files.writeString(
            srt,
            "1\n00:00:01,000 --> 00:00:03,000\nHello there\n\n2\n00:00:04,000 --> 00:00:06,500\n<i>Second</i> line\n",
        )
        val movie = dir.resolve("media/movies/Talk (2021)/Talk (2021).mkv")
        Files.createDirectories(movie.parent)
        // The clip's video, its audio twice and the subtitles: streams 0, 1, 2 and 3.
        val mux = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", source.toString(), "-i", srt.toString(),
            "-map", "0:v", "-map", "0:a", "-map", "0:a", "-map", "1:s", "-c", "copy", "-c:s", "srt", movie.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(mux.waitFor(60, TimeUnit.SECONDS) && mux.exitValue() == 0, "FFmpeg muxed the subtitles")
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
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        try {
            val library = runtime.libraries!!.create(
                "Movies",
                LibraryKind.MOVIES,
                dir.resolve("media/movies").toString(),
            )
            runtime.libraries!!.scan(library.id)
            val item = runtime.libraries!!.catalog.items(library.id, null, false, null, 10).items.single()
            val detail = runtime.libraries!!.catalog.item(item.id)
            assertEquals(listOf(3), detail.subtitles.map { it.index })
            assertEquals(listOf(1, 2), detail.audio.map { it.index })
            application { backendModule(store, MutableClock(), runtime, token, config = config) }

            val subs = client.get("/api/v1/library/items/${item.id}/subtitles/3") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, subs.status)
            assertEquals(ContentType.parse("text/vtt"), subs.contentType()?.withoutParameters())
            val vtt = subs.bodyAsText()
            assertTrue(vtt.startsWith("WEBVTT"), vtt)
            assertTrue("00:01.000 --> 00:03.000" in vtt && "Hello there" in vtt, vtt)
            assertEquals(
                vtt,
                client.get("/api/v1/library/items/${item.id}/subtitles/3") {
                    bearerAuth(token)
                }.bodyAsText(),
                "the second read is the kept copy",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/library/items/${item.id}/subtitles/1") {
                    bearerAuth(token)
                }.status,
                "stream 1 is audio",
            )
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/library/items/${item.id}/subtitles/3").status)

            val video = detail.videoCodec!!
            suspend fun open(audioIndex: Int?) = json.decodeFromString<VodSession>(
                client.post("/api/v1/vod-sessions") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            OpenVodPlayback.serializer(),
                            OpenVodPlayback(
                                item.id,
                                ClientProfile(listOf("mkv"), listOf(video), listOf("aac")),
                                audioIndex = audioIndex,
                            ),
                        ),
                    )
                }.bodyAsText(),
            )
            assertEquals(Wire.VodMode.DIRECT, open(1).mode, "the default stream plays directly")
            assertEquals(Wire.VodMode.REMUX, open(2).mode, "another stream is carried in the stream")
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `subtitle files beside a video are read as text and picture subtitles are drawn into the picture`() =
        testApplication {
            val dir = Files.createTempDirectory("sofa-vod-sidecars-")
            val source = dir.resolve("plain.mkv")
            clip(source)
            val sup = dir.resolve("embedded.sup")
            PgsFixture.write(sup, listOf(1_000L..3_000L, 4_000L..6_500L), 320, 180)
            val movie = dir.resolve("media/movies/Talk (2021)/Talk (2021).mkv")
            Files.createDirectories(movie.parent)
            // The clip's video and audio, and PGS subtitles as stream 2.
            val mux = ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", source.toString(), "-i", sup.toString(),
                "-map", "0:v", "-map", "0:a", "-map", "1:s", "-c", "copy", movie.toString(),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(mux.waitFor(60, TimeUnit.SECONDS) && mux.exitValue() == 0, "FFmpeg muxed the subtitles")
            Files.writeString(
                movie.resolveSibling("Talk (2021).en.forced.srt"),
                "1\n00:00:01,000 --> 00:00:03,000\nHello there\n",
            )
            PgsFixture.write(movie.resolveSibling("Talk (2021).de.sup"), listOf(2_000L..5_000L), 320, 180)
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
                    maxTranscodes = 2,
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
                val item = runtime.libraries!!.catalog.items(library.id, null, false, null, 10).items.single()
                val detail = runtime.libraries!!.catalog.item(item.id)
                assertEquals(
                    listOf(2 to "hdmv_pgs_subtitle", 1000 to "hdmv_pgs_subtitle", 1001 to "subrip"),
                    detail.subtitles.map {
                        it.index to
                            it.codec
                    },
                )
                assertEquals("de", detail.subtitles[1].language)
                assertTrue(detail.subtitles[2].forced)
                application { backendModule(store, MutableClock(), runtime, token, config = config) }

                val vtt = client.get("/api/v1/library/items/${item.id}/subtitles/1001") {
                    bearerAuth(token)
                }.bodyAsText()
                assertTrue(vtt.startsWith("WEBVTT") && "Hello there" in vtt, vtt)
                assertEquals(
                    HttpStatusCode.UnsupportedMediaType,
                    client.get("/api/v1/library/items/${item.id}/subtitles/2") {
                        bearerAuth(token)
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.UnsupportedMediaType,
                    client.get("/api/v1/library/items/${item.id}/subtitles/1000") {
                        bearerAuth(token)
                    }.status,
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/v1/library/items/${item.id}/subtitles/1002") {
                        bearerAuth(token)
                    }.status,
                )

                val profile = ClientProfile(listOf("mkv"), listOf(detail.videoCodec!!), listOf("aac"))
                suspend fun open(subtitleIndex: Int?) = client.post("/api/v1/vod-sessions") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            OpenVodPlayback.serializer(),
                            OpenVodPlayback(item.id, profile, startMs = 1_500, subtitleIndex = subtitleIndex),
                        ),
                    )
                }
                for (index in listOf(2, 1000)) {
                    val session = json.decodeFromString<VodSession>(open(index).bodyAsText())
                    assertEquals(
                        Wire.VodMode.TRANSCODE,
                        session.mode,
                        "subtitle $index is drawn, so the picture is encoded",
                    )
                    assertTrue(
                        "seg0.ts" in client.get(session.descriptor.url).bodyAsText(),
                        "FFmpeg drew subtitle $index",
                    )
                    assertEquals(
                        HttpStatusCode.NoContent,
                        client.delete("/api/v1/vod-sessions/${session.id}") {
                            bearerAuth(token)
                        }.status,
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, open(1001).status, "text is drawn by the player")
                assertEquals(HttpStatusCode.NotFound, open(7).status)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `a video with no picture shows a frame and the portal lists what plays from the libraries`() = testApplication {
        val dir = Files.createTempDirectory("sofa-vod-portal-")
        val admin = "admin".repeat(8)
        val episode = dir.resolve("media/tv/Show (2020)/Season 01/Show - S01E01 - Pilot.mkv")
        clip(episode)
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
            )
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        try {
            assertEquals(
                listOf("Media", "Recordings"),
                runtime.libraries!!.roots.map {
                    it.label
                },
                "recordings can be a library too",
            )
            val library = runtime.libraries!!.create("TV", LibraryKind.SHOWS, dir.resolve("media/tv").toString())
            runtime.libraries!!.scan(library.id)
            val show = runtime.libraries!!.catalog.items(library.id, null, false, null, 10).items.single()
            val season = runtime.libraries!!.catalog.item(show.id).children.single()
            val pilot = runtime.libraries!!.catalog.item(season.id).children.single()
            application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }

            val still = client.get(pilot.poster!!)
            assertEquals(HttpStatusCode.OK, still.status)
            assertEquals(ContentType.Image.JPEG, still.contentType())
            val bytes = still.readRawBytes()
            assertTrue(
                bytes.size > 100 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
                "a JPEG of the frame",
            )
            assertEquals(
                pilot.poster!!.substringBefore('?').replace("poster", "backdrop"),
                pilot.backdrop!!.substringBefore('?'),
                "the frame is its backdrop too",
            )
            assertNull(show.poster, "a show has no frame of its own")

            client.post("/api/v1/vod-sessions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(OpenVodPlayback.serializer(), OpenVodPlayback(pilot.id, ClientProfile())))
            }
            val browser = createClient {
                install(HttpCookies)
                followRedirects = false
            }
            browser.submitForm("/admin/login", parameters { append("token", admin) })
            val home = browser.get("/admin").bodyAsText()
            assertTrue(
                "Show · S1E1 · Pilot" in home && "transcode" in home,
                "Watching now lists the episode being converted",
            )
            val id = runtime.vod!!.snapshot().single().id
            val csrf = Regex("""name="csrf" value="([^"]*)"""").find(home)!!.groupValues[1]
            assertEquals(
                HttpStatusCode.SeeOther,
                browser.submitForm(
                    "/admin/vod/$id/stop",
                    parameters {
                        append("csrf", csrf)
                    },
                ).status,
            )
            assertTrue(runtime.vod!!.snapshot().isEmpty(), "Stop ends it")
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a whole playlist lists the item and converts from the segment a player asks for`() = testApplication {
        val dir = Files.createTempDirectory("sofa-vod-whole-")
        clip(dir.resolve("media/movies/Long (2020)/Long (2020).mkv"))
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
            val item = runtime.libraries!!.catalog.items(library.id, null, false, null, 10).items.single()
            application { backendModule(store, MutableClock(), runtime, token, config = config) }
            suspend fun open(whole: Boolean, profile: ClientProfile = ClientProfile()) =
                json.decodeFromString<VodSession>(
                    client.post("/api/v1/vod-sessions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            Json.encodeToString(
                                OpenVodPlayback.serializer(),
                                OpenVodPlayback(item.id, profile, startMs = 4_500, wholePlaylist = whole),
                            ),
                        )
                    }.bodyAsText(),
                )

            val session = open(true)
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
            val renewed = json.decodeFromString<VodSession>(
                client.patch("/api/v1/vod-sessions/${session.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"startMs":6000}""")
                }.bodyAsText(),
            )
            assertEquals(session.descriptor.url, renewed.descriptor.url, "a restart only renews a whole playlist")
            client.delete("/api/v1/vod-sessions/${session.id}") { bearerAuth(token) }

            val copied =
                open(
                    true,
                    ClientProfile(
                        listOf("mp4"),
                        listOf(
                            item.let {
                                runtime.libraries!!.catalog.item(it.id).videoCodec!!
                            },
                        ),
                        listOf("aac"),
                    ),
                )
            assertEquals(4_500, copied.startMs, "a copy keeps the playlist that starts where asked")
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }
}
