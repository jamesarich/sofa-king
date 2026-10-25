package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.TokenScope
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
import dev.jamesrich.sofaking.contract.RuleMatch
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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

    private class Api(
        val runtime: LiveRuntime,
        val channelId: String,
        val programStart: Instant,
        val recordings: Path,
    ) {
        /** A day after the program, when the guide has nothing. */
        val window: Instant = programStart.plusSeconds(86_400)

        fun schedule(startsAt: String, endsAt: String? = null, channelId: String = this.channelId) =
            Json.encodeToString(ScheduleRecording.serializer(), ScheduleRecording(channelId, startsAt, endsAt))
    }

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

    private suspend fun ApplicationTestBuilder.scheduleMovie(api: Api): Recording {
        val created = send(HttpMethod.Post, RECORDINGS, api.schedule(api.programStart.toString()))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return json.decodeFromString(created.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.createNews(api: Api): RecordingRule {
        val body = """{"title":"  News  ","channelId":"${api.channelId}","keep":3,"priority":2}"""
        val created = send(HttpMethod.Post, RULES, body)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return json.decodeFromString(created.bodyAsText())
    }

    @Test fun `a malformed or impossible schedule answers 400`() = testApplication {
        dvrApi {
            for (body in listOf(
                "not json",
                """{"startsAt":"$window"}""",
                schedule("tomorrow"),
                schedule(window.toString(), window.minusSeconds(60).toString()),
                schedule(window.toString(), window.plus(Duration.ofHours(13)).toString()),
            )) {
                assertError(HttpStatusCode.BadRequest, "INVALID_REQUEST", send(HttpMethod.Post, RECORDINGS, body))
            }
        }
    }

    @Test fun `a schedule for an unknown channel or program answers 404, and for an ended one 409`() = testApplication {
        dvrApi {
            assertError(
                HttpStatusCode.NotFound,
                Wire.ErrorCode.CHANNEL_NOT_FOUND,
                send(HttpMethod.Post, RECORDINGS, schedule(window.toString(), channelId = "nope")),
            )
            assertError(
                HttpStatusCode.NotFound,
                "PROGRAM_NOT_FOUND",
                send(HttpMethod.Post, RECORDINGS, schedule(programStart.plusSeconds(60).toString())),
            )
            val past = Instant.now().minusSeconds(7200)
            assertError(
                HttpStatusCode.Conflict,
                "PROGRAM_ENDED",
                send(HttpMethod.Post, RECORDINGS, schedule(past.toString(), past.plusSeconds(1800).toString())),
            )
        }
    }

    @Test fun `a scheduled program answers 201 with the guide's title and default paddings`() = testApplication {
        dvrApi {
            val movie = scheduleMovie(this)
            assertEquals(Wire.RecordingState.SCHEDULED, movie.state)
            assertEquals("Movie Night", movie.title)
            assertEquals(60, movie.startPaddingSeconds)
            assertEquals(120, movie.endPaddingSeconds)
            val read = send(HttpMethod.Get, "$RECORDINGS/${movie.id}")
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(movie, json.decodeFromString<Recording>(read.bodyAsText()))
            assertError(HttpStatusCode.NotFound, "RECORDING_NOT_FOUND", send(HttpMethod.Get, "$RECORDINGS/nope"))
        }
    }

    @Test fun `the calendar feed lists a scheduled program for a read token or the API token`() = testApplication {
        dvrApi {
            val movie = scheduleMovie(this)
            val read = runtime.tokens.create("Calendar", TokenScope.READ).second
            val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
            val feed = client.get("/output/recordings.ics?token=$read")
            assertEquals(HttpStatusCode.OK, feed.status, feed.bodyAsText())
            assertEquals(ContentType("text", "calendar"), feed.contentType()?.withoutParameters())
            val lines = feed.bodyAsText().split("\r\n")
            assertTrue("UID:${movie.id}@sofa-king" in lines, feed.bodyAsText())
            assertTrue("SUMMARY:Movie Night" in lines, feed.bodyAsText())
            assertEquals(HttpStatusCode.OK, client.get("/output/recordings.ics") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/output/recordings.ics?token=$hook").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/output/recordings.ics").status)
        }
    }

    @Test fun `a recording PATCH answers 200, a malformed one 400 and an unknown recording 404`() = testApplication {
        dvrApi {
            val movie = scheduleMovie(this)
            val body = """{"protected":true,"positionMs":-5,"watched":true}"""
            val patched = send(HttpMethod.Patch, "$RECORDINGS/${movie.id}", body)
            assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
            val changed = json.decodeFromString<Recording>(patched.bodyAsText())
            assertTrue(changed.protected)
            assertTrue(changed.watched)
            assertEquals(0, changed.positionMs, "a negative position is stored as the start")
            assertError(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                send(HttpMethod.Patch, "$RECORDINGS/${movie.id}", """{"protected":"""),
            )
            assertError(
                HttpStatusCode.NotFound,
                "RECORDING_NOT_FOUND",
                send(HttpMethod.Patch, "$RECORDINGS/nope", """{"protected":true}"""),
            )
        }
    }

    @Test fun `a deleted recording answers 204 and then 404`() = testApplication {
        dvrApi {
            val movie = scheduleMovie(this)
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "$RECORDINGS/${movie.id}").status)
            for (method in listOf(HttpMethod.Get, HttpMethod.Delete)) {
                assertError(HttpStatusCode.NotFound, "RECORDING_NOT_FOUND", send(method, "$RECORDINGS/${movie.id}"))
            }
            assertEquals(
                emptyList(),
                json.decodeFromString<RecordingList>(send(HttpMethod.Get, RECORDINGS).bodyAsText()).recordings,
            )
        }
    }

    @Test fun `a malformed or out-of-range rule answers 400, and one for an unknown channel 404`() = testApplication {
        dvrApi {
            for (body in listOf(
                "[]",
                """{"title":"   "}""",
                """{"title":"${"x".repeat(201)}"}""",
                """{"title":"News","keep":1001}""",
                """{"title":"News","priority":101}""",
                """{"title":"News","startPaddingSeconds":3601}""",
                """{"title":"News","endPaddingSeconds":-1}""",
                """{"title":"News","match":{"kind":"genre"}}""",
                """{"title":"News","match":{"kind":"keyword"}}""",
                """{"title":"News","match":{"kind":"team","terms":["${"x".repeat(101)}"]}}""",
            )) {
                assertError(HttpStatusCode.BadRequest, "INVALID_REQUEST", send(HttpMethod.Post, RULES, body))
            }
            assertError(
                HttpStatusCode.NotFound,
                Wire.ErrorCode.CHANNEL_NOT_FOUND,
                send(HttpMethod.Post, RULES, """{"title":"News","channelId":"nope"}"""),
            )
        }
    }

    @Test fun `a created rule trims its title, takes the default paddings and lists`() = testApplication {
        dvrApi {
            val news = createNews(this)
            assertEquals("News", news.title)
            assertEquals(channelId, news.channelId)
            assertEquals(60, news.startPaddingSeconds, "unset paddings take the server's defaults")
            assertEquals(120, news.endPaddingSeconds)
            val listed = send(HttpMethod.Get, RULES)
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(listOf(news), json.decodeFromString<RecordingRuleList>(listed.bodyAsText()).rules)
        }
    }

    @Test fun `a team rule takes its title and Sports when it names neither, and lists its match`() = testApplication {
        dvrApi {
            val team = json.decodeFromString<RecordingRule>(
                send(HttpMethod.Post, RULES, """{"title":" Packers ","match":{"kind":"team"}}""").bodyAsText(),
            )
            assertEquals(RuleMatch(Wire.RuleMatchKind.TEAM, listOf("Packers"), listOf("Sports")), team.match)
            val news = createNews(this)
            assertEquals(null, news.match, "a title rule sends no match")
            val listed = json.decodeFromString<RecordingRuleList>(send(HttpMethod.Get, RULES).bodyAsText()).rules
            assertEquals(listOf(team, news), listed, "the match survives the store")
        }
    }

    @Test fun `a rule PATCH keeps unset fields, and a bad one answers 400 or 404`() = testApplication {
        dvrApi {
            val news = createNews(this)
            val body = """{"anyChannel":true,"keep":0,"endPaddingSeconds":600}"""
            val patched = send(HttpMethod.Patch, "$RULES/${news.id}", body)
            assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
            val any = json.decodeFromString<RecordingRule>(patched.bodyAsText())
            assertNull(any.channelId)
            assertEquals(0, any.keep)
            assertEquals(600, any.endPaddingSeconds)
            assertEquals(2, any.priority, "an unset field keeps its value")
            assertError(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                send(HttpMethod.Patch, "$RULES/${news.id}", """{"priority":-101}"""),
            )
            assertError(
                HttpStatusCode.NotFound,
                Wire.ErrorCode.CHANNEL_NOT_FOUND,
                send(HttpMethod.Patch, "$RULES/${news.id}", """{"channelId":"nope"}"""),
            )
            assertError(
                HttpStatusCode.NotFound,
                "RULE_NOT_FOUND",
                send(HttpMethod.Patch, "$RULES/nope", """{"keep":1}"""),
            )
        }
    }

    @Test fun `a deleted rule answers 204 and then 404`() = testApplication {
        dvrApi {
            val news = createNews(this)
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "$RULES/${news.id}").status)
            assertError(HttpStatusCode.NotFound, "RULE_NOT_FOUND", send(HttpMethod.Delete, "$RULES/${news.id}"))
            assertEquals(
                emptyList(),
                json.decodeFromString<RecordingRuleList>(send(HttpMethod.Get, RULES).bodyAsText()).rules,
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

    private companion object {
        const val RECORDINGS = "/api/v1/recordings"
        const val RULES = "/api/v1/recording-rules"
    }
}
