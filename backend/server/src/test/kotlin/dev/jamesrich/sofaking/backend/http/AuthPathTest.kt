package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.header
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthPathTest {
    private val apiToken = "api".repeat(12)
    private val adminToken = "admin".repeat(8)

    @Test fun `token guard decides on the path routing resolves`() = testApplication {
        val dir = Files.createTempDirectory("sofa-auth-path-")
        val store = openStore(dir)
        try {
            application {
                backendModule(
                    store,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                    apiToken = apiToken,
                    adminToken = adminToken,
                )
                routing {
                    get("/api/v1/channels") { call.respondText("channels") }
                    get("/api/v1/admin/sessions") { call.respondText("sessions") }
                }
            }
            for (path in listOf("//api/v1/channels", "/%61pi/v1/channels", "/api//v1/channels", "/api/v1/channels/")) {
                assertEquals(HttpStatusCode.Unauthorized, raw(path).status, "no token: $path")
                assertEquals(HttpStatusCode.Unauthorized, raw(path, apiToken.reversed()).status, "wrong token: $path")
            }
            for (path in listOf(
                "//api/v1/admin/sessions",
                "/%61pi/v1/admin/sessions",
                "/api//v1/admin/sessions",
                "/api/v1/%61dmin/sessions",
            )) {
                assertEquals(HttpStatusCode.Unauthorized, raw(path).status, "no token: $path")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                raw("/api/v1/%61dmin/sessions", apiToken).status,
                "API token on admin route",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                raw("//api/v1/admin/sessions", apiToken).status,
                "API token on admin route",
            )
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `authorized requests reach their handlers under any spelling`() = testApplication {
        val dir = Files.createTempDirectory("sofa-auth-path-")
        val store = openStore(dir)
        try {
            application {
                backendModule(
                    store,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                    apiToken = apiToken,
                    adminToken = adminToken,
                )
                routing {
                    get("/api/v1/channels") { call.respondText("channels") }
                    get("/api/v1/admin/sessions") { call.respondText("sessions") }
                }
            }
            assertEquals(HttpStatusCode.OK, raw("/api/v1/bootstrap", apiToken).status)
            assertEquals("channels", raw("/api/v1/channels", apiToken).bodyAsText())
            assertEquals("channels", raw("//api/v1/channels", apiToken).bodyAsText())
            assertEquals("sessions", raw("/api/v1/admin/sessions", adminToken).bodyAsText())
            assertEquals("sessions", raw("//api/v1/admin/sessions", adminToken).bodyAsText())
            assertEquals("sessions", raw("/api/v1/%61dmin/sessions", adminToken).bodyAsText())
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `segments that are dot segments or contain a slash are rejected`() = testApplication {
        val dir = Files.createTempDirectory("sofa-auth-path-")
        val store = openStore(dir)
        try {
            application {
                backendModule(
                    store,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                    apiToken = apiToken,
                    adminToken = adminToken,
                )
            }
            for (path in listOf(
                "/api/v1/%2e%2e/channels",
                "/api/v1/./channels",
                "/api/v1/%2e/channels",
                "/api/v1/%2Fchannels",
                "/api/v1/%2F%2Fchannels",
                "/api/v1/x%0A2026-10-20%20WARN%20forged",
                "/api/v1/x%00y",
            )) {
                assertEquals(HttpStatusCode.BadRequest, raw(path, apiToken).status, "$path")
                assertEquals(HttpStatusCode.BadRequest, raw(path).status, "no token: $path")
            }
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `without a token the API answers only this machine, not another site's page, a rebound name or a proxy`() =
        testApplication {
            val dir = Files.createTempDirectory("sofa-auth-open-")
            val store = openStore(dir)
            try {
                application {
                    backendModule(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), apiToken = null, adminToken = null)
                    routing { post("/api/v1/admin/policy/starter") { call.respondText("written") } }
                }
                suspend fun starter(vararg headers: Pair<String, String>) =
                    client.post("/api/v1/admin/policy/starter") {
                        headers.forEach { (name, value) -> header(name, value) }
                    }
                assertEquals("written", starter().bodyAsText())
                assertEquals("written", starter(HttpHeaders.Host to "127.0.0.1:8080").bodyAsText())
                assertEquals(
                    "written",
                    starter(
                        HttpHeaders.Host to "[::1]:8080",
                        HttpHeaders.Origin to "http://localhost:8081",
                    ).bodyAsText(),
                )
                for (refused in listOf(
                    arrayOf(HttpHeaders.Origin to "https://attacker.test"),
                    arrayOf(HttpHeaders.Origin to "null"),
                    arrayOf(HttpHeaders.Host to "rebound.attacker.test:8080"),
                    arrayOf(HttpHeaders.XForwardedFor to "203.0.113.9"),
                    arrayOf(HttpHeaders.Forwarded to "for=203.0.113.9"),
                )) {
                    val response = starter(*refused)
                    assertEquals(HttpStatusCode.Forbidden, response.status, refused.joinToString())
                    assertTrue("LOOPBACK_ONLY" in response.bodyAsText())
                }
            } finally {
                store.close()
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `the admin token opens the outputs off this machine when it is the only token`() = testApplication {
        val dir = Files.createTempDirectory("sofa-auth-output-admin-")
        val store = openStore(dir)
        try {
            application {
                backendModule(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), apiToken = null, adminToken = "root")
                routing {
                    get("/output/playlist.m3u") {
                        authorizeOutput(call, outputToken = null, apiToken = null, adminToken = "root")
                        call.respondText("playlist")
                    }
                }
            }
            val admin = client.get("/output/playlist.m3u") {
                header(HttpHeaders.XForwardedFor, "203.0.113.9")
                header(HttpHeaders.Authorization, "Bearer root")
            }
            assertEquals("playlist", admin.bodyAsText())
            val wrong = client.get("/output/playlist.m3u") {
                header(HttpHeaders.XForwardedFor, "203.0.113.9")
                header(HttpHeaders.Authorization, "Bearer toor")
            }
            assertEquals(HttpStatusCode.Forbidden, wrong.status)
            assertTrue("LOOPBACK_ONLY" in wrong.bodyAsText())
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `without a token the outputs answer only this machine, not a proxy`() = testApplication {
        val dir = Files.createTempDirectory("sofa-auth-output-")
        val store = openStore(dir)
        try {
            application {
                backendModule(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), apiToken = null, adminToken = null)
                routing {
                    get("/output/playlist.m3u") {
                        authorizeOutput(call, outputToken = null, apiToken = null)
                        call.respondText("playlist")
                    }
                }
            }
            assertEquals("playlist", client.get("/output/playlist.m3u").bodyAsText())
            val proxied = client.get("/output/playlist.m3u") { header(HttpHeaders.XForwardedFor, "203.0.113.9") }
            assertEquals(HttpStatusCode.Forbidden, proxied.status)
            assertTrue("LOOPBACK_ONLY" in proxied.bodyAsText())
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun ApplicationTestBuilder.raw(path: String, token: String? = null): HttpResponse = client.get {
        url { encodedPath = path }
        token?.let { bearerAuth(it) }
    }
}
