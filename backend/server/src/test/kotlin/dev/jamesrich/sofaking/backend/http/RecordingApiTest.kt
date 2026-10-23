package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.dvr.StoredRecording
import dev.jamesrich.sofaking.backend.pollFor
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.RecordingRuleList
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.port
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.path
import io.ktor.http.plus
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DVR's writes over HTTP: what each answers, the error shapes of bad requests, and stopping a running recording.
 */
class RecordingApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val xmltvTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    private class Api(val runtime: LiveRuntime, val channelId: String, val programStart: Instant, val recordings: Path)

    /** The live fixture channel with a guide holding one `Movie Night` an hour from now, behind the API token. */
    private suspend fun ApplicationTestBuilder.dvrApi(block: suspend Api.() -> Unit) {
        val dir = Files.createTempDirectory("sofa-recording-api-")
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600)
        val body = (
            "<?xml version=\"1.0\"?><tv><channel id=\"live\"><display-name>Live</display-name></channel>" +
                "<programme channel=\"live\" start=\"${xmltvTime.format(start)}\" " +
                "stop=\"${xmltvTime.format(start.plusSeconds(7200))}\"><title>Movie Night</title></programme></tv>"
            ).toByteArray()
        val guide = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/guide.xml") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        MediaFixture().use { media ->
            val feed = media.feed.copy(guideUrl = "http://127.0.0.1:${guide.address.port}/guide.xml")
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(feed)))
            // No space floor, so cleanup never deletes the recordings the test writes; no
            // Comskip, so nothing scans them.
            val config = ServerConfig(
                "127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true,
                hdhr = HdhrConfig(
                    enabled = false,
                ),
                updateImage = null,
                recordingsFreeGib = 0, comskip = null,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, Clock.systemUTC(), probes = false)
            try {
                runtime.feeds.refresh(feed)
                runtime.adoptAll()
                application { backendModule(store, Clock.systemUTC(), runtime, token, config = config) }
                val live = runtime.lineup.entries().single { it.number == "2" }
                Api(runtime, live.id, start, config.recordingsDirectory).block()
            } finally {
                runtime.close()
                store.close()
                guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): HttpResponse = client.request(path) {
        this.method = method
        bearerAuth(token)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Fails unless [response] is [status] with the API's error document carrying [code]. */
    private suspend fun assertError(status: HttpStatusCode, code: String, response: HttpResponse) {
        val text = response.bodyAsText()
        assertEquals(status, response.status, text)
        val error = json.decodeFromString<ApiError>(text)
        assertEquals(code, error.code, text)
        assertTrue(error.message.isNotBlank(), text)
        assertFalse(error.retryable, text)
    }

    @Test fun `recording writes answer 201, 200 and 204, and a bad request answers 400 or 404 with its error code`() =
        testApplication {
            dvrApi {
                val recordings = "/api/v1/recordings"
                val window = programStart.plusSeconds(86_400)
                fun schedule(startsAt: String, endsAt: String? = null, channelId: String = this.channelId) =
                    Json.encodeToString(ScheduleRecording.serializer(), ScheduleRecording(channelId, startsAt, endsAt))

                assertError(HttpStatusCode.BadRequest, "INVALID_REQUEST", send(HttpMethod.Post, recordings, "not json"))
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, recordings, """{"startsAt":"$window"}"""),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, recordings, schedule("tomorrow")),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, recordings, schedule(window.toString(), window.minusSeconds(60).toString())),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(
                        HttpMethod.Post,
                        recordings,
                        schedule(window.toString(), window.plus(Duration.ofHours(13)).toString()),
                    ),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    Wire.ErrorCode.CHANNEL_NOT_FOUND,
                    send(HttpMethod.Post, recordings, schedule(window.toString(), channelId = "nope")),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    "PROGRAM_NOT_FOUND",
                    send(HttpMethod.Post, recordings, schedule(programStart.plusSeconds(60).toString())),
                )
                val past = Instant.now().minusSeconds(7200)
                assertError(
                    HttpStatusCode.Conflict,
                    "PROGRAM_ENDED",
                    send(HttpMethod.Post, recordings, schedule(past.toString(), past.plusSeconds(1800).toString())),
                )

                val created = send(HttpMethod.Post, recordings, schedule(programStart.toString()))
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val movie = json.decodeFromString<Recording>(created.bodyAsText())
                assertEquals(Wire.RecordingState.SCHEDULED, movie.state)
                assertEquals("Movie Night", movie.title)
                assertEquals(60, movie.startPaddingSeconds)
                assertEquals(120, movie.endPaddingSeconds)

                val read = send(HttpMethod.Get, "$recordings/${movie.id}")
                assertEquals(HttpStatusCode.OK, read.status)
                assertEquals(movie, json.decodeFromString<Recording>(read.bodyAsText()))
                assertError(HttpStatusCode.NotFound, "RECORDING_NOT_FOUND", send(HttpMethod.Get, "$recordings/nope"))

                val patched =
                    send(
                        HttpMethod.Patch,
                        "$recordings/${movie.id}",
                        """{"protected":true,"positionMs":-5,"watched":true}""",
                    )
                assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
                val changed = json.decodeFromString<Recording>(patched.bodyAsText())
                assertTrue(changed.protected)
                assertTrue(changed.watched)
                assertEquals(0, changed.positionMs, "a negative position is stored as the start")
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Patch, "$recordings/${movie.id}", """{"protected":"""),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    "RECORDING_NOT_FOUND",
                    send(HttpMethod.Patch, "$recordings/nope", """{"protected":true}"""),
                )

                assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "$recordings/${movie.id}").status)
                assertError(
                    HttpStatusCode.NotFound,
                    "RECORDING_NOT_FOUND",
                    send(HttpMethod.Get, "$recordings/${movie.id}"),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    "RECORDING_NOT_FOUND",
                    send(HttpMethod.Delete, "$recordings/${movie.id}"),
                )
                assertEquals(
                    emptyList(),
                    json.decodeFromString<RecordingList>(send(HttpMethod.Get, recordings).bodyAsText()).recordings,
                )
            }
        }

    @Test fun `rule writes answer 201, 200 and 204, and a bad rule answers 400 or 404 with its error code`() =
        testApplication {
            dvrApi {
                val rules = "/api/v1/recording-rules"
                assertError(HttpStatusCode.BadRequest, "INVALID_REQUEST", send(HttpMethod.Post, rules, "[]"))
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, rules, """{"title":"   "}"""),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, rules, """{"title":"${"x".repeat(201)}"}"""),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, rules, """{"title":"News","keep":1001}"""),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, rules, """{"title":"News","priority":101}"""),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, rules, """{"title":"News","startPaddingSeconds":3601}"""),
                )
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Post, rules, """{"title":"News","endPaddingSeconds":-1}"""),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    Wire.ErrorCode.CHANNEL_NOT_FOUND,
                    send(HttpMethod.Post, rules, """{"title":"News","channelId":"nope"}"""),
                )

                val created =
                    send(
                        HttpMethod.Post,
                        rules,
                        """{"title":"  News  ","channelId":"$channelId","keep":3,"priority":2}""",
                    )
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val news = json.decodeFromString<RecordingRule>(created.bodyAsText())
                assertEquals("News", news.title)
                assertEquals(channelId, news.channelId)
                assertEquals(60, news.startPaddingSeconds, "unset paddings take the server's defaults")
                assertEquals(120, news.endPaddingSeconds)
                val listed = send(HttpMethod.Get, rules)
                assertEquals(HttpStatusCode.OK, listed.status)
                assertEquals(listOf(news), json.decodeFromString<RecordingRuleList>(listed.bodyAsText()).rules)

                val patched =
                    send(
                        HttpMethod.Patch,
                        "$rules/${news.id}",
                        """{"anyChannel":true,"keep":0,"endPaddingSeconds":600}""",
                    )
                assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
                val any = json.decodeFromString<RecordingRule>(patched.bodyAsText())
                assertNull(any.channelId)
                assertEquals(0, any.keep)
                assertEquals(600, any.endPaddingSeconds)
                assertEquals(2, any.priority, "an unset field keeps its value")
                assertError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REQUEST",
                    send(HttpMethod.Patch, "$rules/${news.id}", """{"priority":-101}"""),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    Wire.ErrorCode.CHANNEL_NOT_FOUND,
                    send(HttpMethod.Patch, "$rules/${news.id}", """{"channelId":"nope"}"""),
                )
                assertError(
                    HttpStatusCode.NotFound,
                    "RULE_NOT_FOUND",
                    send(HttpMethod.Patch, "$rules/nope", """{"keep":1}"""),
                )

                assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "$rules/${news.id}").status)
                assertError(HttpStatusCode.NotFound, "RULE_NOT_FOUND", send(HttpMethod.Delete, "$rules/${news.id}"))
                assertEquals(
                    emptyList(),
                    json.decodeFromString<RecordingRuleList>(send(HttpMethod.Get, rules).bodyAsText()).rules,
                )
            }
        }

    @Test fun `the recording and rule lists keep every field of their wire fixtures`() = testApplication {
        dvrApi {
            val rule = json.decodeFromString<RecordingRule>(
                send(
                    HttpMethod.Post,
                    "/api/v1/recording-rules",
                    """{"title":"Evening News","channelId":"$channelId","keep":5}""",
                ).bodyAsText(),
            )
            val rulesBody = send(HttpMethod.Get, "/api/v1/recording-rules").bodyAsText()
            assertKeepsWireFixture("v1/recording-rule-list.json", rulesBody)

            // A kept episode with every field the server fills: guide facts, a file, a
            // short-stop reason and commercial breaks.
            val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(1))
            runtime.store.addRecording(
                StoredRecording(
                    "kept", rule.id, channelId, "Live", start,
                    start.plusSeconds(
                        1800,
                    ),
                    Duration.ofSeconds(60), Duration.ofSeconds(120), "Evening News",
                    episodeTitle = "Late Edition", description = "The day's headlines.", season = 3, episode = 12,
                    year = 2026, image = "/logos/news",
                    state = Wire.RecordingState.COMPLETED, reason = "STOPPED",
                    path = "TV/Evening News/Season 03/Evening News - S03E12 - Late Edition.ts",
                    bytes = 18_800,
                    protected = true, positionMs = 60_000, watched = true, createdAt = start, updatedAt = start,
                    commercials = Wire.CommercialScan.DONE, breaks = listOf(RecordingBreak(1_000, 2_500)),
                ),
            )
            val listBody = send(HttpMethod.Get, "/api/v1/recordings").bodyAsText()
            assertEquals(listOf("kept"), json.decodeFromString<RecordingList>(listBody).recordings.map { it.id })
            assertKeepsWireFixture("v1/recording-list.json", listBody)
        }
    }

    @Test
    fun `deleting a running recording stops it and keeps what it wrote, with the reason STOPPED`() = testApplication {
        dvrApi {
            val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val created = send(
                HttpMethod.Post,
                "/api/v1/recordings",
                Json.encodeToString(
                    ScheduleRecording.serializer(),
                    ScheduleRecording(channelId, now.toString(), now.plusSeconds(600).toString(), "Long Game"),
                ),
            )
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val id = json.decodeFromString<Recording>(created.bodyAsText()).id
            suspend fun current() =
                json.decodeFromString<Recording>(send(HttpMethod.Get, "/api/v1/recordings/$id").bodyAsText())
            val partial = pollFor(30_000, 100) {
                val running = current()
                val file = running.path?.let(recordings::resolve)
                file?.takeIf {
                    running.state == Wire.RecordingState.RECORDING && Files.exists(it) && Files.size(it) > 0
                }
            }

            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/recordings/$id").status)
            val stopped = current()
            assertEquals(Wire.RecordingState.COMPLETED, stopped.state, "a stopped recording is kept")
            assertEquals("STOPPED", stopped.reason)
            val path = assertNotNull(stopped.path)
            assertFalse(path.startsWith(".inprogress"), path)
            assertTrue(Files.size(recordings.resolve(path)) > 0)
            assertEquals(Files.size(recordings.resolve(path)), stopped.bytes)
            assertFalse(Files.exists(partial))
            assertEquals(0, runtime.sessions.count(), "the recorder let go of its session")
            assertEquals(
                listOf(id),
                json.decodeFromString<RecordingList>(
                    send(HttpMethod.Get, "/api/v1/recordings").bodyAsText(),
                ).recordings.map {
                    it.id
                },
            )

            // Deleting it again deletes the kept file.
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/v1/recordings/$id").status)
            assertFalse(Files.exists(recordings.resolve(path)))
            assertError(HttpStatusCode.NotFound, "RECORDING_NOT_FOUND", send(HttpMethod.Get, "/api/v1/recordings/$id"))
        }
    }
}
