package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.client.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.*

class LiveTvTest {
    @Test fun `authenticated native API relays manifest resources and byte ranges then releases`() = testApplication {
        val dir = Files.createTempDirectory("sofa-live-api-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
            try {
                runtime.feeds.refresh(fixture.feed)
                application { backendModule(store, MutableClock(), runtime, token) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/channels").status)
                val catalogResponse = client.get("/api/v1/channels") { bearerAuth(token) }
                val catalog = Json.decodeFromString<Catalog>(catalogResponse.bodyAsText())
                assertEquals(1, catalog.channels.size)
                assertFalse(catalogResponse.bodyAsText().contains(fixture.base))
                assertEquals(HttpStatusCode.NotModified, client.get("/api/v1/channels") { bearerAuth(token); header(HttpHeaders.IfNoneMatch, catalogResponse.headers[HttpHeaders.ETag]!!) }.status)
                val query = GuideQuery(listOf(catalog.channels.single().id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")
                val guideResponse = client.post("/api/v1/guide/query") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(query)) }
                assertEquals("Morning", Json.decodeFromString<GuideWindow>(guideResponse.bodyAsText()).channels.single().programmes.single().title)
                val request = OpenPlayback(catalog.channels.single().id, "preview", "api-test")
                val opened = client.post("/api/v1/playback-sessions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(request)) }
                assertEquals(HttpStatusCode.Created, opened.status)
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
                val sdk = SofaBackendClient("http://localhost", token, client)
                val handle = sdk.openPlayback(sdk.catalog().channels.single().id, "preview")
                val initial = (handle.state.value as PlaybackState.Ready).session
                assertEquals(initial.descriptor, handle.promoteToWatching().descriptor)
                handle.close()
                assertEquals(PlaybackState.Closed, handle.state.value)
                assertEquals(HttpStatusCode.Gone, client.get(initial.descriptor.url).status)
                sdk.close()
                val source = Json.decodeFromString<List<SourceStatus>>(client.get("/api/v1/sources") { bearerAuth(token) }.bodyAsText()).single()
                assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/sources/${source.id}") { setBody("{}") }.status)
                client.patch("/api/v1/sources/${source.id}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Json.encodeToString(UpdateSource(true))) }
                assertFalse(Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels.single().playbackEligible)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
