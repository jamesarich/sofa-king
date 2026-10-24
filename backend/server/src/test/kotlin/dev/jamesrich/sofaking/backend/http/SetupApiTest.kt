package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.catalog.loadPolicy
import dev.jamesrich.sofaking.backend.catalog.readSourceDocument
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupApiTest {
    private val json = Json { encodeDefaults = false }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    @Test fun `the source file is read under the admin token only`() = setupApi {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/source-file") { bearerAuth(token) }.status)
        assertEquals(listOf("fixture"), document().providers.map { it.id })
    }

    @Test fun `an invalid source file is refused and nothing restarts`() = setupApi {
        val bad = putSources("""{"providers":[{"id":"x","type":"native","service":"nope"}]}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("INVALID_SOURCES" in bad.bodyAsText())
        assertEquals(0, restarts)
    }

    @Test fun `a valid source file is written as sent and the server restarts`() = setupApi {
        val next = withPluto()
        val put = putSources(json.encodeToString(next))
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertTrue("\"restarting\":true" in put.bodyAsText())
        assertEquals(next, readSourceDocument(sources))
        assertEquals(1, restarts)
    }

    @Test fun `the starter policy is written once, natives first`() = setupApi {
        putSources(json.encodeToString(withPluto()))
        val starter = client.post("/api/v1/admin/policy/starter") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.Created, starter.status, starter.bodyAsText())
        assertEquals(
            listOf("pluto", "fixture"),
            loadPolicy(dir.resolve("policy.json"), setOf("fixture", "pluto")).policy.providers.map { it.id },
        )
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/admin/policy/starter") { bearerAuth(admin) }.status)
    }

    /** A server over the fixture's feed that counts the restarts it asks for. */
    private inner class SetupApi(val app: ApplicationTestBuilder, val dir: Path, val sources: Path) {
        val client get() = app.client
        var restarts = 0

        suspend fun document(): SourceDocument =
            Json.decodeFromString(client.get("/api/v1/admin/source-file") { bearerAuth(admin) }.bodyAsText())

        /** The source file with Pluto TV added. */
        suspend fun withPluto(): SourceDocument = document().let {
            it.copy(providers = it.providers + FeedConfig("pluto", type = "native", service = "pluto"))
        }

        suspend fun putSources(body: String) = client.put("/api/v1/admin/source-file") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun setupApi(block: suspend SetupApi.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-setup-api-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed))},"guides":[]}""")
            val store = openStore(dir)
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    sources,
                    token,
                    allowLoopbackUpstreams = true,
                    adminToken = admin,
                    updateImage = null,
                )
            val setup = SetupApi(this, dir, sources)
            val runtime = LiveRuntime.create(
                store,
                config,
                MutableClock(),
                policyDebounceMillis = 3_600_000,
                probes = false,
                restart = { setup.restarts++ },
            )
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                setup.block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
