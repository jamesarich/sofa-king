package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.HistoryEntry
import dev.jamesrich.sofaking.backend.api.ServerEvent
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.playback.HistoryReason
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    @Test fun `the event stream sends ready, then each session's start and end, to the admin token only`() = history {
        val sse = app.createClient { install(SSE) }
        val received = mutableListOf<ServerSentEvent>()
        sse.sse(EVENT_STREAM_PATH, request = { bearerAuth(admin) }) {
            incoming.filter { it.event != null }.take(3).collect { event ->
                received += event
                if (event.event == READY_EVENT) watchAndStop()
            }
        }
        assertEquals(
            listOf(READY_EVENT, EventType.SESSION_STARTED, EventType.SESSION_ENDED),
            received.map { it.event },
        )
        val ended = json.decodeFromString<ServerEvent>(received.last().data!!)
        assertEquals(EventType.SESSION_ENDED, ended.type)
        assertEquals("tv-1", ended.data.getValue("clientId").jsonPrimitive.content)
        assertEquals(HistoryReason.RELEASED, ended.data.getValue("reason").jsonPrimitive.content)
        for (refused in listOf(device, token, "")) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(EVENT_STREAM_PATH) { bearerAuth(refused) }.status,
            )
        }
    }

    @Test fun `the history lists ended sessions with who played them and filters them`() = history {
        val id = watchAndStop()
        val response = client.get("/api/v1/admin/history") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val entry = json.decodeFromString<List<HistoryEntry>>(response.bodyAsText()).single()
        assertEquals(id, entry.id)
        assertEquals("live", entry.kind)
        assertEquals("tv-1", entry.clientId)
        assertEquals("watch", entry.mode)
        assertEquals(HistoryReason.RELEASED, entry.reason)
        assertEquals("[]", client.get("/api/v1/admin/history?kind=vod") { bearerAuth(admin) }.bodyAsText())
        assertEquals("[]", client.get("/api/v1/admin/history?client=tv-2") { bearerAuth(admin) }.bodyAsText())
        for (bad in listOf("since=yesterday", "kind=radio", "limit=0", "limit=1001")) {
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/v1/admin/history?$bad") { bearerAuth(admin) }.status,
                bad,
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/history") { bearerAuth(device) }.status)
    }

    /** A server with the fixture's channels, the admin token and a paired device, `tv-1`. */
    private inner class History(val app: ApplicationTestBuilder, val runtime: LiveRuntime, val device: String) {
        val client get() = app.client

        /** Opens a watch session as the device and releases it; the session's id. */
        suspend fun watchAndStop(): String {
            val channel = runtime.lineup.entries().first().id
            val opened = client.post("/api/v1/playback-sessions") {
                bearerAuth(device)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(OpenPlayback(channel, "watch", "history-test")))
            }
            assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
            val id = json.decodeFromString<PlaybackSession>(opened.bodyAsText()).id
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/v1/playback-sessions/$id") { bearerAuth(device) }.status,
            )
            return id
        }
    }

    private fun history(block: suspend History.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-history-api-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json { encodeDefaults = true }.encodeToString(listOf(fixture.feed)))
            val config = ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                sources,
                token,
                allowLoopbackUpstreams = true,
                adminToken = admin,
                hdhr = HdhrConfig(enabled = false),
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                runtime.feeds.refresh(fixture.feed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
                History(this, runtime, runtime.tokens.pair("tv-1", "TV")).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
