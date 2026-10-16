package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.*

class SetupApiTest {
    private val json = Json { encodeDefaults = false }

    @Test fun `the source file is read and replaced, and the starter policy written, under the admin token, each write restarting`() = testApplication {
        val dir = Files.createTempDirectory("sofa-setup-api-")
        val token = "secret".repeat(8)
        val admin = "curator".repeat(6)
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, """{"providers":${json.encodeToString(listOf(fixture.feed))},"guides":[]}""")
            val store = openStore(dir)
            val config = ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin, updateImage = null)
            var restarts = 0
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false, restart = { restarts++ })
            try {
                application { backendModule(store, MutableClock(), runtime, token, admin) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/source-file") { bearerAuth(token) }.status)

                val document = Json.decodeFromString<SourceDocument>(client.get("/api/v1/admin/source-file") { bearerAuth(admin) }.bodyAsText())
                assertEquals(listOf("fixture"), document.providers.map { it.id })

                val bad = client.put("/api/v1/admin/source-file") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"providers":[{"id":"x","type":"native","service":"nope"}]}""") }
                assertEquals(HttpStatusCode.BadRequest, bad.status)
                assertTrue("INVALID_SOURCES" in bad.bodyAsText())
                assertEquals(0, restarts)

                val next = document.copy(providers = document.providers + FeedConfig("pluto", type = "native", service = "pluto"))
                val put = client.put("/api/v1/admin/source-file") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody(json.encodeToString(next)) }
                assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
                assertTrue("\"restarting\":true" in put.bodyAsText())
                assertEquals(next, readSourceDocument(sources))
                assertEquals(1, restarts)

                val starter = client.post("/api/v1/admin/policy/starter") { bearerAuth(admin) }
                assertEquals(HttpStatusCode.Created, starter.status, starter.bodyAsText())
                assertEquals(listOf("pluto", "fixture"), loadPolicy(dir.resolve("policy.json"), setOf("fixture", "pluto")).policy.providers.map { it.id })
                assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/admin/policy/starter") { bearerAuth(admin) }.status)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }
}
