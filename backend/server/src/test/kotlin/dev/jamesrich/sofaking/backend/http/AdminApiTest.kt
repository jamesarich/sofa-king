package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.*

class AdminApiTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `admin API edits the lineup behind its own token and the app sees the result`() = testApplication {
        val dir = Files.createTempDirectory("sofa-admin-api-")
        val token = "secret".repeat(8)
        val admin = "curator".repeat(6)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed))},"guides":${json.encodeToString(listOf(fixture.gzipGuide))}}""")
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin), MutableClock())
            try {
                runtime.feeds.refresh(fixture.feed); runtime.feeds.refresh(fixture.altFeed); runtime.guides.refresh(fixture.gzipGuide)
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                suspend fun HttpRequestBuilder.asAdmin() { bearerAuth(admin); contentType(ContentType.Application.Json) }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/lineup").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/lineup") { bearerAuth(token) }.status, "the API token does not open admin routes")
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/channels") { bearerAuth(admin) }.status, "the admin token does not open app routes")
                assertEquals("[]", client.get("/api/v1/admin/lineup") { bearerAuth(admin) }.bodyAsText())
                assertTrue(Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels.isEmpty())

                val candidates = Json.decodeFromString<CandidatePage>(client.get("/api/v1/admin/candidates?q=stat") { bearerAuth(admin) }.bodyAsText())
                assertEquals(2, candidates.total)
                assertFalse(client.get("/api/v1/admin/candidates") { bearerAuth(admin) }.bodyAsText().contains(fixture.base), "candidates never expose stream URLs")
                val fixtureCandidate = candidates.items.single { it.providerId == "fixture" }
                val altCandidate = candidates.items.single { it.providerId == "alt" }
                assertEquals("station", fixtureCandidate.tvgId); assertTrue(fixtureCandidate.lineupEntryIds.isEmpty())
                assertEquals(1, Json.decodeFromString<CandidatePage>(client.get("/api/v1/admin/candidates?provider=alt") { bearerAuth(admin) }.bodyAsText()).total)

                val guides = Json.decodeFromString<List<GuideStatus>>(client.get("/api/v1/admin/guides") { bearerAuth(admin) }.bodyAsText())
                assertEquals(listOf("gz", "fixture"), guides.map { it.id })
                val guideChannels = Json.decodeFromString<List<GuideChannelInfo>>(client.get("/api/v1/admin/guides/gz/channels?q=other") { bearerAuth(admin) }.bodyAsText())
                assertEquals("other", guideChannels.single().channelId)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/admin/guides/nope/channels") { bearerAuth(admin) }.status)

                val created = client.post("/api/v1/admin/lineup") { asAdmin(); setBody(json.encodeToString(CreateLineupEntry(channelId = fixtureCandidate.channelId))) }
                assertEquals(HttpStatusCode.Created, created.status)
                val entry = Json.decodeFromString<LineupEntry>(created.bodyAsText())
                assertEquals("Station", entry.name); assertEquals(GuideMapping("gz", "station"), entry.guide)
                assertEquals(listOf("UNVERIFIED"), entry.offerings.map { it.status })
                val patched = Json.decodeFromString<LineupEntry>(client.patch("/api/v1/admin/lineup/${entry.id}") {
                    asAdmin(); setBody(json.encodeToString(UpdateLineupEntry(number = "501", offerings = listOf(altCandidate.offerings.single().id, fixtureCandidate.offerings.single().id))))
                }.bodyAsText())
                assertEquals("501", patched.number); assertEquals(listOf("alt", "fixture"), patched.offerings.map { it.providerId })
                assertEquals("Station", patched.name, "an absent field keeps its value")
                assertEquals("manual", patched.createdBy)
                assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/admin/lineup/${entry.id}") { asAdmin(); setBody("""{"number":""}""") }.status)
                assertEquals(HttpStatusCode.NotFound, client.patch("/api/v1/admin/lineup/missing") { asAdmin(); setBody("{}") }.status)
                val second = Json.decodeFromString<LineupEntry>(client.post("/api/v1/admin/lineup") { asAdmin(); setBody("""{"number":"7","name":"Manual"}""") }.bodyAsText())
                assertEquals("unassigned", second.guideStatus)
                val ordered = Json.decodeFromString<List<LineupEntry>>(client.put("/api/v1/admin/lineup/order") { asAdmin(); setBody(json.encodeToString(ReorderLineup(listOf(second.id, entry.id)))) }.bodyAsText())
                assertEquals(listOf(second.id, entry.id), ordered.map { it.id }); assertEquals(listOf(0, 1), ordered.map { it.position })

                runtime.guides.refresh(fixture.gzipGuide)
                val catalog = Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText())
                assertEquals(listOf("7", "501"), catalog.channels.map { it.displayNumber })
                assertEquals("available", catalog.channels[1].guideStatus); assertTrue(catalog.channels[1].playbackEligible)
                assertEquals("station", catalog.channels[1].legacyGuideId)
                val window = Json.decodeFromString<GuideWindow>(client.post("/api/v1/guide/query") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(GuideQuery(listOf(entry.id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")))
                }.bodyAsText())
                assertEquals("Morning", window.channels.single().programs.single().title)
                val session = Json.decodeFromString<PlaybackSession>(client.post("/api/v1/playback-sessions") {
                    bearerAuth(token); contentType(ContentType.Application.Json); setBody(json.encodeToString(OpenPlayback(entry.id, "preview", "admin-test")))
                }.bodyAsText())
                assertEquals(entry.id, session.channelId)

                val lineup = Json.decodeFromString<List<LineupEntry>>(client.get("/api/v1/admin/lineup") { bearerAuth(admin) }.bodyAsText())
                assertEquals(2, lineup.size)
                assertEquals(listOf(entry.id), Json.decodeFromString<CandidatePage>(client.get("/api/v1/admin/candidates?provider=alt") { bearerAuth(admin) }.bodyAsText()).items.single().lineupEntryIds)
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/lineup/${second.id}") { bearerAuth(admin) }.status)
                assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/admin/lineup/${second.id}") { bearerAuth(admin) }.status)
                val adopt = Json.decodeFromString<AdoptResult>(client.post("/api/v1/admin/lineup/adopt") { asAdmin(); setBody(json.encodeToString(AdoptProvider("alt"))) }.bodyAsText())
                assertEquals(AdoptResult(0, 1), adopt)
                assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/v1/admin/lineup") { asAdmin(); setBody("""{"name":"${"x".repeat(70_000)}"}""") }.status)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `without an admin token the API token administers the lineup`() = testApplication {
        val dir = Files.createTempDirectory("sofa-admin-fallback-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true), MutableClock())
            try {
                runtime.feeds.refresh(fixture.feed)
                application { backendModule(store, MutableClock(), runtime, token) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/lineup").status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/lineup") { bearerAuth(token) }.status)
                val adopt = client.post("/api/v1/admin/lineup/adopt") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"providerId":"fixture"}""") }
                assertEquals(AdoptResult(1, 0), Json.decodeFromString<AdoptResult>(adopt.bodyAsText()))
                assertEquals(1, Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels.size)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
