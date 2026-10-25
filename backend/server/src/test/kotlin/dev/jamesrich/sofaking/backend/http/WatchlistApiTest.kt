package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Watchlist
import dev.jamesrich.sofaking.contract.WatchlistEntry
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The household watchlist over HTTP: adding titles, their next airing, the error shapes, and removing. */
class WatchlistApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val xmltvTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    private class Api(val channelId: String, val programStart: Instant)

    /** The live fixture channel with a guide holding one `Movie Night` an hour from now, behind the API token. */
    private suspend fun ApplicationTestBuilder.watchlistApi(block: suspend Api.() -> Unit) {
        val dir = Files.createTempDirectory("sofa-watchlist-api-")
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
            val config = ServerConfig(
                "127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true,
                hdhr = HdhrConfig(enabled = false), updateImage = null, recordingsFreeGib = 0, comskip = null,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, Clock.systemUTC(), probes = false)
            try {
                runtime.feeds.refresh(feed)
                runtime.adoptAll()
                application { backendModule(store, Clock.systemUTC(), runtime, token, config = config) }
                Api(runtime.lineup.entries().single { it.number == "2" }.id, start).block()
            } finally {
                runtime.close()
                store.close()
                guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String? = null) =
        client.request(path) {
            this.method = method
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun assertError(status: HttpStatusCode, code: String, response: HttpResponse) {
        val text = response.bodyAsText()
        assertEquals(status, response.status, text)
        assertEquals(code, json.decodeFromString<ApiError>(text).code, text)
    }

    @Test fun `a title entry names its next airing, and adding it again returns the same entry`() = testApplication {
        watchlistApi {
            val created = send(HttpMethod.Post, WATCHLIST, """{"kind":"title","title":"  movie night "}""")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val entry = json.decodeFromString<WatchlistEntry>(created.bodyAsText())
            assertEquals(Wire.WatchlistKind.TITLE, entry.kind)
            assertEquals("movie night", entry.title)
            assertNull(entry.channelId)
            assertEquals("Movie Night", entry.next?.title)
            assertEquals(channelId, entry.next?.channelId)
            assertEquals(programStart.toString(), entry.next?.startsAt)

            val again = send(HttpMethod.Post, WATCHLIST, """{"kind":"title","title":"Movie Night"}""")
            assertEquals(entry.id, json.decodeFromString<WatchlistEntry>(again.bodyAsText()).id)
            val onChannel = send(
                HttpMethod.Post,
                WATCHLIST,
                """{"kind":"title","title":"Movie Night","channelId":"$channelId"}""",
            )
            val channelEntry = json.decodeFromString<WatchlistEntry>(onChannel.bodyAsText())
            assertEquals(channelId, channelEntry.channelId)
            assertEquals(programStart.toString(), channelEntry.next?.startsAt)

            val listBody = send(HttpMethod.Get, WATCHLIST).bodyAsText()
            val listed = json.decodeFromString<Watchlist>(listBody).entries
            assertEquals(setOf(entry.id, channelEntry.id), listed.map { it.id }.toSet())
            assertKeepsWireFixture("v1/watchlist.json", listBody)
        }
    }

    @Test fun `a title with no airing lists without one`() = testApplication {
        watchlistApi {
            val created = send(HttpMethod.Post, WATCHLIST, """{"kind":"title","title":"Late Show"}""")
            assertNull(json.decodeFromString<WatchlistEntry>(created.bodyAsText()).next)
        }
    }

    @Test fun `a bad entry answers 400, and an unknown item 404`() = testApplication {
        watchlistApi {
            for (body in listOf(
                """{"kind":"show","title":"News"}""",
                """{"kind":"title","title":"   "}""",
                """{"kind":"title","title":"${"x".repeat(201)}"}""",
                """{"kind":"title","title":"News","channelId":"nope"}""",
                """{"kind":"item"}""",
            )) {
                assertError(HttpStatusCode.BadRequest, "INVALID_REQUEST", send(HttpMethod.Post, WATCHLIST, body))
            }
            assertError(
                HttpStatusCode.NotFound,
                "ITEM_NOT_FOUND",
                send(HttpMethod.Post, WATCHLIST, """{"kind":"item","itemId":"nope"}"""),
            )
        }
    }

    @Test fun `a removed entry answers 204 and then 404`() = testApplication {
        watchlistApi {
            val created = send(HttpMethod.Post, WATCHLIST, """{"kind":"title","title":"Movie Night"}""")
            val id = json.decodeFromString<WatchlistEntry>(created.bodyAsText()).id
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "$WATCHLIST/$id").status)
            assertEquals(
                emptyList(),
                json.decodeFromString<Watchlist>(send(HttpMethod.Get, WATCHLIST).bodyAsText()).entries,
            )
            assertError(HttpStatusCode.NotFound, "WATCHLIST_ENTRY_NOT_FOUND", send(HttpMethod.Delete, "$WATCHLIST/$id"))
        }
    }

    private companion object {
        const val WATCHLIST = "/api/v1/watchlist"
    }
}
