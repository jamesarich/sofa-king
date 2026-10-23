package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.dvr.StoredRecording
import dev.jamesrich.sofaking.backend.library.LibraryCatalog
import dev.jamesrich.sofaking.backend.library.MediaProbe
import dev.jamesrich.sofaking.backend.library.PlayableFile
import dev.jamesrich.sofaking.backend.library.VodSessions
import dev.jamesrich.sofaking.backend.library.ffprobeBeside
import dev.jamesrich.sofaking.backend.library.itemId
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.VodSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.path
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordingPlaybackTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)

    /** A six-second MPEG-TS clip, as the recorder writes. */
    private fun clip(file: Path) {
        Files.createDirectories(file.parent)
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "6", "-pix_fmt", "yuv420p", "-g", "15", "-c:v", "mpeg2video", "-c:a", "aac", "-f", "mpegts", file.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "FFmpeg could not write the clip" }
    }

    @Test fun `a finished recording plays through a VOD session, directly when the client takes the file`() =
        testApplication {
            val dir = Files.createTempDirectory("sofa-rec-play-")
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
            val clock = MutableClock()
            val runtime = LiveRuntime.create(store, config, clock, probes = false)
            try {
                val now = clock.instant()
                clip(config.recordingsDirectory.resolve("TV/News/News (2026-10-10).ts"))
                store.addRecording(
                    StoredRecording(
                        "done", null, "c", "Channel C",
                        now.minusSeconds(
                            3600,
                        ),
                        now.minusSeconds(1800), Duration.ZERO, Duration.ZERO, "News",
                        state = Wire.RecordingState.COMPLETED, path = "TV/News/News (2026-10-10).ts", createdAt = now, updatedAt = now,
                    ),
                )
                application { backendModule(store, clock, runtime, token, config = config) }
                suspend fun open(id: String) = client.post("/api/v1/vod-sessions") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            OpenVodPlayback.serializer(),
                            OpenVodPlayback(profile = tv, recordingId = id),
                        ),
                    )
                }

                val opened = open("done")
                assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
                val direct = json.decodeFromString<VodSession>(opened.bodyAsText())
                assertEquals(Wire.VodMode.DIRECT, direct.mode)
                assertEquals("done", direct.recordingId)
                assertEquals("", direct.itemId)
                assertEquals("video/mp2t", direct.descriptor.mimeType)
                assertTrue((direct.durationMs ?: 0) in 5_000..7_000, "the length FFprobe read: ${direct.durationMs}")
                assertEquals(
                    HttpStatusCode.PartialContent,
                    client.get(direct.descriptor.url) {
                        header(HttpHeaders.Range, "bytes=0-187")
                    }.status,
                )
                assertEquals(HttpStatusCode.NotFound, open("nope").status)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `a recording still being made plays as HLS that follows the file, once it holds something`() = runBlocking {
        val dir = Files.createTempDirectory("sofa-rec-live-")
        val store = openStore(dir)
        val clock = MutableClock()
        val growing = dir.resolve("recordings/.inprogress/Live.ts").also(::clip)
        val empty = Files.createFile(dir.resolve("recordings/.inprogress/Empty.ts"))
        val files = mapOf(
            "live" to PlayableFile(growing, 3_600_000, growing = true),
            "empty" to PlayableFile(empty, 3_600_000, growing = true),
        )
        val vod = VodSessions(
            LibraryCatalog(store, clock) { 0L },
            clock,
            "ffmpeg",
            dir.resolve("transcode"),
            recordings = { files[it] },
            probe = MediaProbe(ffprobeBeside("ffmpeg"))::probe,
        )
        try {
            val live = vod.open(OpenVodPlayback(profile = tv, recordingId = "live"))
            assertEquals(Wire.VodMode.REMUX, live.mode, "never direct while the file grows")
            assertEquals(3_600_000, live.durationMs, "the length the recording will have")
            assertEquals("live", live.recordingId)
            val failure = assertFailsWith<ApiFailure> { vod.open(OpenVodPlayback(profile = tv, recordingId = "empty")) }
            assertEquals(409, failure.status)
            assertEquals("RECORDING_NOT_READY", failure.code)
            assertEquals(
                404,
                assertFailsWith<ApiFailure> {
                    vod.open(OpenVodPlayback(profile = tv, recordingId = "nope"))
                }.status,
            )
        } finally {
            vod.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    private val tv = ClientProfile(listOf("ts", "mkv"), listOf("mpeg2video", "h264"), listOf("aac"))
}
