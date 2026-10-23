package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.SourceStatus
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.query
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveTvTest {
    @Test fun `authenticated native API relays manifest resources and byte ranges then releases`() = testApplication {
        val dir = Files.createTempDirectory("sofa-live-api-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
            try {
                runtime.feeds.refresh(fixture.feed); runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token) }
                val unauthorized = client.get("/api/v1/channels")
                assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
                assertKeepsWireFixture("v1/api-error.json", unauthorized.bodyAsText())
                assertKeepsWireFixture("v1/bootstrap.json", client.get("/api/v1/bootstrap") { bearerAuth(token) }.bodyAsText())
                val catalogResponse = client.get("/api/v1/channels") { bearerAuth(token) }
                assertKeepsWireFixture("v1/catalog.json", catalogResponse.bodyAsText())
                val catalog = Json.decodeFromString<Catalog>(catalogResponse.bodyAsText())
                assertEquals(1, catalog.channels.size)
                assertFalse(catalogResponse.bodyAsText().contains(fixture.base))
                assertEquals(HttpStatusCode.NotModified, client.get("/api/v1/channels") { bearerAuth(token); header(HttpHeaders.IfNoneMatch, catalogResponse.headers[HttpHeaders.ETag]!!) }.status)
                val query = GuideQuery(listOf(catalog.channels.single().id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")
                val guideResponse = client.post("/api/v1/guide/query") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(query)) }
                assertKeepsWireFixture("v1/guide-window.json", guideResponse.bodyAsText())
                assertEquals("Morning", Json.decodeFromString<GuideWindow>(guideResponse.bodyAsText()).channels.single().programmes.single().title)
                val request = OpenPlayback(catalog.channels.single().id, "preview", "api-test")
                val opened = client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(request)) }
                assertEquals(HttpStatusCode.Created, opened.status)
                assertKeepsWireFixture("v1/playback-session.json", opened.bodyAsText())
                val session = Json.decodeFromString<PlaybackSession>(opened.bodyAsText())
                val master = client.get(session.descriptor.url).bodyAsText()
                assertFalse(master.contains(fixture.base))
                val variant = master.lineSequence().first { it.startsWith("/media/") }
                val playlist = client.get(variant).bodyAsText()
                val keyPath = Regex("URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
                assertEquals("0123456789abcdef", client.get(keyPath).bodyAsText())
                val segment = playlist.lineSequence().first { it.startsWith("/media/") }
                val range = client.get(segment) { header(HttpHeaders.Range, "bytes=0-3") }
                assertEquals(HttpStatusCode.PartialContent, range.status); assertEquals("fixt", range.bodyAsText())
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/playback-sessions/${session.id}") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Gone, client.get(session.descriptor.url).status)
                val preview = Json.decodeFromString<PlaybackSession>(client.post("/api/v1/playback-sessions") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(OpenPlayback(catalog.channels.single().id, Wire.Intent.PREVIEW, "api-promote")))
                }.bodyAsText())
                val promoted = client.patch("/api/v1/playback-sessions/${preview.id}") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody("""{"intent":"watch","fromANewerApp":true}""")
                }
                val watching = Json.decodeFromString<PlaybackSession>(promoted.bodyAsText())
                assertEquals(Wire.Intent.WATCH, watching.intent)
                assertEquals(preview.descriptor, watching.descriptor)
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/playback-sessions/${preview.id}") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Gone, client.get(preview.descriptor.url).status)
                val source = Json.decodeFromString<List<SourceStatus>>(client.get("/api/v1/sources") { bearerAuth(token) }.bodyAsText()).single()
                assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/sources/${source.id}") { setBody("{}") }.status)
                client.patch("/api/v1/sources/${source.id}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(UpdateSource(true))) }
                assertFalse(Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels.single().playbackEligible)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
