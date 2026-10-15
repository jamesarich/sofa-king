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

class StatusApiTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    @Test fun `the status takes the admin token, never the API token, on a LAN server`() = testApplication {
        val dir = Files.createTempDirectory("sofa-status-auth-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, json.encodeToString(listOf(fixture.feed)))
            val config = ServerConfig("0.0.0.0", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, hdhr = HdhrConfig(enabled = false))
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/status").status)
                val withApiToken = client.get("/api/v1/admin/status") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Unauthorized, withApiToken.status, "the API token does not open the status")
                assertEquals("AUTH_REQUIRED", Json.decodeFromString<ApiError>(withApiToken.bodyAsText()).code)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/status") { bearerAuth(admin) }.status)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `the status sums up the lineup, sources, imports, uptime and revision`() = testApplication {
        val dir = Files.createTempDirectory("sofa-status-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(
                sources,
                """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed))},"guides":${json.encodeToString(listOf(fixture.gzipGuide, fixture.brokenGuide))}}""",
            )
            val clock = MutableClock()
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, revision = "abc1234")
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, clock, probes = false)
            try {
                runtime.feeds.refresh(fixture.feed); runtime.feeds.refresh(fixture.altFeed)
                runtime.guides.refresh(fixture.gzipGuide); runtime.guides.refresh(fixture.brokenGuide)
                application { backendModule(store, clock, runtime, token, admin, config = config) }
                val adopt = client.post("/api/v1/admin/lineup/adopt") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"providerId":"fixture"}""") }
                assertEquals(HttpStatusCode.OK, adopt.status)
                val started = clock.now
                clock.now = started.plusSeconds(3725)

                val status = Json.decodeFromString<ServerStatus>(client.get("/api/v1/admin/status") { bearerAuth(admin) }.bodyAsText())
                assertEquals("abc1234", status.revision)
                assertEquals(started.toString(), status.startedAt)
                assertEquals(3725, status.uptimeSeconds)
                assertEquals(100, status.schemaVersion)
                val lineup = runtime.lineup.entries()
                assertEquals(StatusChannels(lineup.size, lineup.count { it.enabled }, runtime.catalog.catalog().channels.count { it.playbackEligible }), status.channels)
                assertTrue(status.channels.total > 0)
                val feedSources = runtime.feeds.sources()
                assertEquals(feedSources.size, status.sources.total)
                assertEquals(feedSources.size, status.sources.byStatus.values.sum())
                assertEquals(0, status.sources.manuallyDisabled)

                assertEquals(2, status.providers.total)
                assertEquals(emptyList(), status.providers.failing)
                assertNotNull(status.providers.lastImportAt)
                assertEquals(listOf("broken"), status.guides.failing.map { it.id })
                assertEquals("IMPORT_FAILED", status.guides.failing.single().errorCode)
                assertEquals(listOf("gz", "broken", "fixture").sorted(), runtime.guides.statuses().map { it.id }.sorted())
                assertEquals(3, status.guides.total)

                assertNull(status.policy, "no lineup policy is configured")
                assertNull(status.plex, "Plex sync is not configured")
                assertEquals(0, status.activeSessions)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
