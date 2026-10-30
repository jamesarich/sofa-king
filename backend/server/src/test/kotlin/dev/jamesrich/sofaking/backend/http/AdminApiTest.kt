package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.AdoptProvider
import dev.jamesrich.sofaking.backend.api.AdoptResult
import dev.jamesrich.sofaking.backend.api.CandidatePage
import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.GuideChannelInfo
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.LineupEntry
import dev.jamesrich.sofaking.backend.api.ReorderLineup
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.query
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminApiTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `the admin token opens every route and the API token only the app's`() = adminApi {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/lineup").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/admin/lineup") { bearerAuth(token) }.status,
            "the API token does not open admin routes",
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/channels") { bearerAuth(admin) }.status,
            "the admin token opens app routes too",
        )
        assertEquals("[]", client.get("/api/v1/admin/lineup") { bearerAuth(admin) }.bodyAsText())
        assertTrue(app<Catalog>("/api/v1/channels").channels.isEmpty())
    }

    @Test fun `candidates match a name or a provider and never expose stream URLs`() = adminApi {
        val candidates = admin<CandidatePage>("/api/v1/admin/candidates?q=stat")
        assertEquals(2, candidates.total)
        assertFalse(
            client.get("/api/v1/admin/candidates") { bearerAuth(admin) }.bodyAsText().contains(fixture.base),
            "candidates never expose stream URLs",
        )
        val fixtureCandidate = candidates.items.single { it.providerId == "fixture" }
        assertEquals("station", fixtureCandidate.tvgId)
        assertTrue(fixtureCandidate.lineupEntryIds.isEmpty())
        assertEquals(1, admin<CandidatePage>("/api/v1/admin/candidates?provider=alt").total)
    }

    @Test fun `guides list in order and search their channels`() = adminApi {
        assertEquals(listOf("gz", "fixture"), admin<List<GuideStatus>>("/api/v1/admin/guides").map { it.id })
        val channels = admin<List<GuideChannelInfo>>("/api/v1/admin/guides/gz/channels?q=other")
        assertEquals("other", channels.single().channelId)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/admin/guides/nope/channels") { bearerAuth(admin) }.status,
        )
    }

    @Test fun `a lineup entry is created from a candidate, patched and reordered`() = adminApi {
        val created = client.post("/api/v1/admin/lineup") {
            asAdmin()
            setBody(json.encodeToString(CreateLineupEntry(channelId = candidate("fixture").channelId)))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val entry = Json.decodeFromString<LineupEntry>(created.bodyAsText())
        assertEquals("Station", entry.name)
        assertEquals(GuideMapping("gz", "station"), entry.guide)
        assertEquals(listOf("UNVERIFIED"), entry.offerings.map { it.status })
        val patched = renumber(entry)
        assertEquals("501", patched.number)
        assertEquals(listOf("alt", "fixture"), patched.offerings.map { it.providerId })
        assertEquals("Station", patched.name, "an absent field keeps its value")
        assertEquals("manual", patched.createdBy)
        assertEquals(HttpStatusCode.BadRequest, patch("/api/v1/admin/lineup/${entry.id}", """{"number":""}"""))
        assertEquals(HttpStatusCode.NotFound, patch("/api/v1/admin/lineup/missing", "{}"))
        val second = manual()
        assertEquals("unassigned", second.guideStatus)
        val ordered = reorder(second, entry)
        assertEquals(listOf(second.id, entry.id), ordered.map { it.id })
        assertEquals(listOf(0, 1), ordered.map { it.position })
    }

    @Test fun `the app sees the edited lineup in its channels, guide and playback`() = adminApi {
        val entry = renumber(station())
        reorder(manual(), entry)
        runtime.guides.refresh(fixture.gzipGuide)
        val catalog = app<Catalog>("/api/v1/channels")
        assertEquals(listOf("7", "501"), catalog.channels.map { it.displayNumber })
        assertEquals("available", catalog.channels[1].guideStatus)
        assertTrue(catalog.channels[1].playbackEligible)
        assertEquals("station", catalog.channels[1].legacyGuideId)
        val window = Json.decodeFromString<GuideWindow>(
            client.post("/api/v1/guide/query") {
                asApp()
                setBody(
                    json.encodeToString(GuideQuery(listOf(entry.id), "2026-10-10T13:00:00Z", "2026-10-10T14:00:00Z")),
                )
            }.bodyAsText(),
        )
        assertEquals("Morning", window.channels.single().programs.single().title)
        val session = Json.decodeFromString<PlaybackSession>(
            client.post("/api/v1/playback-sessions") {
                asApp()
                setBody(json.encodeToString(OpenPlayback(entry.id, "preview", "admin-test")))
            }.bodyAsText(),
        )
        assertEquals(entry.id, session.channelId)
    }

    @Test fun `candidates name their entries, a deleted entry is gone and adopting adds what is left`() = adminApi {
        val entry = renumber(station())
        val second = manual()
        assertEquals(2, admin<List<LineupEntry>>("/api/v1/admin/lineup").size)
        assertEquals(
            listOf(entry.id),
            admin<CandidatePage>("/api/v1/admin/candidates?provider=alt").items.single().lineupEntryIds,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/admin/lineup/${second.id}") { bearerAuth(admin) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/v1/admin/lineup/${second.id}") { bearerAuth(admin) }.status,
        )
        val adopt = client.post("/api/v1/admin/lineup/adopt") {
            asAdmin()
            setBody(json.encodeToString(AdoptProvider("alt")))
        }
        assertEquals(AdoptResult(0, 1), Json.decodeFromString<AdoptResult>(adopt.bodyAsText()))
    }

    @Test fun `an oversized admin request answers 413`() = adminApi {
        val response = client.post("/api/v1/admin/lineup") {
            asAdmin()
            setBody("""{"name":"${"x".repeat(70_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test fun `without an admin token the API token administers the lineup`() = testApplication {
        val dir = Files.createTempDirectory("sofa-admin-fallback-")
        val token = "secret".repeat(8)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.feed)
                application { backendModule(store, MutableClock(), runtime, token) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/lineup").status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/lineup") { bearerAuth(token) }.status)
                val adopt = client.post("/api/v1/admin/lineup/adopt") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"providerId":"fixture"}""")
                }
                assertEquals(AdoptResult(1, 0), Json.decodeFromString<AdoptResult>(adopt.bodyAsText()))
                assertEquals(
                    1,
                    Json.decodeFromString<Catalog>(
                        client.get("/api/v1/channels") {
                            bearerAuth(token)
                        }.bodyAsText(),
                    ).channels.size,
                )
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    /** The server over the fixture's two feeds and gzip guide, every one refreshed, with an API and an admin token. */
    private inner class AdminApi(val app: ApplicationTestBuilder, val fixture: Fixture, val runtime: LiveRuntime) {
        val client get() = app.client
        val token = TOKEN
        val admin = ADMIN

        fun HttpRequestBuilder.asAdmin() {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
        }

        fun HttpRequestBuilder.asApp() {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
        }

        suspend inline fun <reified T> admin(path: String): T =
            Json.decodeFromString(client.get(path) { bearerAuth(admin) }.bodyAsText())

        suspend inline fun <reified T> app(path: String): T =
            Json.decodeFromString(client.get(path) { bearerAuth(token) }.bodyAsText())

        suspend fun candidate(provider: String) =
            admin<CandidatePage>("/api/v1/admin/candidates?q=stat").items.single { it.providerId == provider }

        suspend fun patch(path: String, body: String) = client.patch(path) {
            asAdmin()
            setBody(body)
        }.status

        /** The fixture's station added to the lineup. */
        suspend fun station(): LineupEntry = Json.decodeFromString(
            client.post("/api/v1/admin/lineup") {
                asAdmin()
                setBody(json.encodeToString(CreateLineupEntry(channelId = candidate("fixture").channelId)))
            }.bodyAsText(),
        )

        /** [entry] numbered 501 and played from the alt feed first, then the fixture's. */
        suspend fun renumber(entry: LineupEntry): LineupEntry {
            val offerings = listOf(candidate("alt"), candidate("fixture")).map { it.offerings.single().id }
            return Json.decodeFromString(
                client.patch("/api/v1/admin/lineup/${entry.id}") {
                    asAdmin()
                    setBody(json.encodeToString(UpdateLineupEntry(number = "501", offerings = offerings)))
                }.bodyAsText(),
            )
        }

        /** An entry made by hand, with no channel behind it. */
        suspend fun manual(): LineupEntry = Json.decodeFromString(
            client.post("/api/v1/admin/lineup") {
                asAdmin()
                setBody("""{"number":"7","name":"Manual"}""")
            }.bodyAsText(),
        )

        suspend fun reorder(vararg entries: LineupEntry): List<LineupEntry> = Json.decodeFromString(
            client.put("/api/v1/admin/lineup/order") {
                asAdmin()
                setBody(json.encodeToString(ReorderLineup(entries.map { it.id })))
            }.bodyAsText(),
        )
    }

    private fun adminApi(block: suspend AdminApi.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-admin-api-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(
                sources,
                """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed))},"guides":""" +
                    """${json.encodeToString(listOf(fixture.gzipGuide))}}""",
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, TOKEN, allowLoopbackUpstreams = true, adminToken = ADMIN),
                MutableClock(),
            )
            try {
                runtime.feeds.refresh(fixture.feed)
                runtime.feeds.refresh(fixture.altFeed)
                runtime.guides.refresh(fixture.gzipGuide)
                application { backendModule(store, MutableClock(), runtime, TOKEN, ADMIN) }
                AdminApi(this, fixture, runtime).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    private companion object {
        val TOKEN = "secret".repeat(8)
        val ADMIN = "curator".repeat(6)
    }
}
