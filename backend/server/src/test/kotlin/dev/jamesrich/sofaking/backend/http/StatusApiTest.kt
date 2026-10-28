package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.ServerStatus
import dev.jamesrich.sofaking.backend.api.StatusChannels
import dev.jamesrich.sofaking.backend.api.StatusLibraries
import dev.jamesrich.sofaking.backend.api.StatusRecordings
import dev.jamesrich.sofaking.backend.api.StatusSummary
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusApiTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    @Test fun `the status takes the admin token, never the API token, on a LAN server`() = testApplication {
        val dir = Files.createTempDirectory("sofa-status-auth-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, json.encodeToString(listOf(fixture.feed)))
            val config =
                ServerConfig(
                    "0.0.0.0",
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
                application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/status").status)
                val withApiToken = client.get("/api/v1/admin/status") { bearerAuth(token) }
                assertEquals(HttpStatusCode.Unauthorized, withApiToken.status, "the API token does not open the status")
                assertEquals("AUTH_REQUIRED", Json.decodeFromString<ApiError>(withApiToken.bodyAsText()).code)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/status") { bearerAuth(admin) }.status)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `the dashboard summary takes a read, API or device token and no other`() = testApplication {
        val dir = Files.createTempDirectory("sofa-status-summary-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, json.encodeToString(listOf(fixture.feed)))
            val config = ServerConfig(
                "127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin,
                hdhr = HdhrConfig(enabled = false), revision = "abc1234",
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                runtime.feeds.refresh(fixture.feed)
                application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
                val read = runtime.tokens.create("Homepage", TokenScope.READ).second
                val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
                val device = runtime.tokens.pair("tv-1", "TV")
                val response = client.get("/api/v1/status") { bearerAuth(read) }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val summary = Json.decodeFromString<StatusSummary>(response.bodyAsText())
                assertEquals("abc1234", summary.revision)
                assertEquals(0, summary.sessions)
                assertEquals(StatusRecordings(0, 0, 0), summary.recordings)
                assertEquals(StatusLibraries(0, 0, 0, 0, 0), summary.libraries)
                assertNull(summary.updateAvailable, "no update check has run")
                for (opens in listOf(token, device)) {
                    assertEquals(HttpStatusCode.OK, client.get("/api/v1/status") { bearerAuth(opens) }.status)
                }
                for (refused in listOf(hook, admin, "")) {
                    assertEquals(
                        HttpStatusCode.Unauthorized,
                        client.get("/api/v1/status") { bearerAuth(refused) }.status,
                    )
                }
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `the status names the revision, start time, uptime and schema`() = status { status, _, started ->
        assertEquals("abc1234", status.revision)
        assertEquals(started.toString(), status.startedAt)
        assertEquals(3725, status.uptimeSeconds)
        assertEquals(221, status.schemaVersion)
    }

    @Test fun `the status counts the lineup's channels and the sources`() = status { status, runtime, _ ->
        val lineup = runtime.lineup.entries()
        assertEquals(
            StatusChannels(
                lineup.size,
                lineup.count { it.enabled },
                runtime.catalog.catalog().channels.count { it.playbackEligible },
            ),
            status.channels,
        )
        assertTrue(status.channels.total > 0)
        val feedSources = runtime.feeds.sources()
        assertEquals(feedSources.size, status.sources.total)
        assertEquals(feedSources.size, status.sources.byStatus.values.sum())
        assertEquals(0, status.sources.manuallyDisabled)
    }

    @Test fun `the status counts the providers and guides and names the failing ones`() = status { status, runtime, _ ->
        assertEquals(2, status.providers.total)
        assertEquals(emptyList(), status.providers.failing)
        assertNotNull(status.providers.lastImportAt)
        assertEquals(listOf("broken"), status.guides.failing.map { it.id })
        assertEquals("IMPORT_FAILED", status.guides.failing.single().errorCode)
        assertEquals(listOf("broken", "fixture", "gz"), runtime.guides.statuses.map { it.id }.sorted())
        assertEquals(3, status.guides.total)
    }

    @Test fun `the status leaves out the policy and Plex when neither is configured`() = status { status, _, _ ->
        assertNull(status.policy, "no lineup policy is configured")
        assertNull(status.plex, "Plex sync is not configured")
        assertEquals(0, status.activeSessions)
    }

    /**
     * The status of a server at revision abc1234 over two feeds and two guides, one broken, with the fixture feed
     * adopted, an hour, two minutes and five seconds after it started.
     */
    private fun status(block: suspend (ServerStatus, LiveRuntime, Instant) -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-status-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(
                sources,
                """{"providers":${json.encodeToString(listOf(fixture.feed, fixture.altFeed))},"guides":""" +
                    """${json.encodeToString(listOf(fixture.gzipGuide, fixture.brokenGuide))}}""",
            )
            val clock = MutableClock()
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    sources,
                    token,
                    allowLoopbackUpstreams = true,
                    adminToken = admin,
                    revision = "abc1234",
                )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, clock, probes = false)
            try {
                runtime.feeds.refresh(fixture.feed)
                runtime.feeds.refresh(fixture.altFeed)
                runtime.guides.refresh(fixture.gzipGuide)
                runtime.guides.refresh(fixture.brokenGuide)
                application { backendModule(store, clock, runtime, token, admin, config = config) }
                val adopt = client.post("/api/v1/admin/lineup/adopt") {
                    bearerAuth(admin)
                    contentType(ContentType.Application.Json)
                    setBody("""{"providerId":"fixture"}""")
                }
                assertEquals(HttpStatusCode.OK, adopt.status)
                val started = clock.now
                clock.now = started.plusSeconds(3725)
                val status = Json.decodeFromString<ServerStatus>(
                    client.get("/api/v1/admin/status") { bearerAuth(admin) }.bodyAsText(),
                )
                block(status, runtime, started)
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
