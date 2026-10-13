package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class PreAuthLimitsTest {
    private val apiToken = "api".repeat(12)
    private val adminToken = "admin".repeat(8)

    @Test fun `an oversized body is refused with 413 before the token is checked`() = testApplication {
        val dir = Files.createTempDirectory("sofa-preauth-")
        val store = openStore(dir)
        try {
            application { backendModule(store, MutableClock(), apiToken = apiToken, adminToken = adminToken) }
            val big = "x".repeat(70_000)
            for (token in listOf(null, apiToken)) {
                val response = client.post("/api/v1/playback-sessions") { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(big) }
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status, "token: $token")
                assertTrue(response.bodyAsText().contains("REQUEST_TOO_LARGE"))
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/admin/login") { contentType(ContentType.Application.FormUrlEncoded); setBody("token=$big") }.status)
        } finally { store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun `only the policy writes may send more than 64 KiB`() {
        assertEquals(64 * 1024L, requestBodyLimit(HttpMethod.Post, "/api/v1/admin/lineup"))
        assertEquals(64 * 1024L, requestBodyLimit(HttpMethod.Post, "/admin/login"))
        assertEquals(64 * 1024L, requestBodyLimit(HttpMethod.Get, "/api/v1/admin/policy"))
        assertEquals(64 * 1024L, requestBodyLimit(HttpMethod.Post, null))
        assertEquals(4 * 1024 * 1024L, requestBodyLimit(HttpMethod.Put, "/api/v1/admin/policy"))
        assertEquals(4 * 1024 * 1024L, requestBodyLimit(HttpMethod.Post, "/admin/providers/edit"))
        assertEquals(4 * 1024 * 1024L, requestBodyLimit(HttpMethod.Post, "/admin/groups/edit"))
        assertEquals("Request exceeds 64 KiB", tooLargeMessage(CONTROL_BODY_LIMIT))
        assertEquals("Request exceeds 4 MiB", tooLargeMessage(POLICY_BODY_LIMIT))
    }

    @Test fun `a rejected bearer is logged with its route and address, never the token or the query`() = testApplication {
        val dir = Files.createTempDirectory("sofa-preauth-")
        val store = openStore(dir)
        val lines = CopyOnWriteArrayList<String>()
        try {
            application {
                backendModule(store, MutableClock(), apiToken = apiToken, adminToken = adminToken)
                attributes.put(AuthFailureLogKey, AuthFailureLog(MutableClock(), sink = { lines += it }))
                routing { get("/api/v1/channels") { call.respondText("channels") } }
            }
            val wrong = apiToken.reversed()
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/channels?token=$apiToken") { bearerAuth(wrong) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/channels") { bearerAuth(apiToken) }.status)
            assertEquals(1, lines.size, "$lines")
            assertTrue(lines.single().matches(Regex("auth failed route=/api/v1/channels remote=\\S+")), lines.single())
            assertTrue(lines.none { wrong in it || apiToken in it || "token=" in it }, "$lines")
        } finally { store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun `auth failure lines are capped each minute and the next one counts what was dropped`() {
        val clock = MutableClock()
        val lines = ArrayList<String>()
        val log = AuthFailureLog(clock, perMinute = 3, sink = { lines += it })
        repeat(5) { log.failed("/admin/login", "203.0.113.9") }
        assertEquals(List(3) { "auth failed route=/admin/login remote=203.0.113.9" }, lines)
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        log.failed("/api/v1/channels", "203.0.113.9")
        assertEquals("auth failed route=/api/v1/channels remote=203.0.113.9 suppressed=2", lines.last())
        log.failed("/api/v1/channels", "203.0.113.9")
        assertEquals("auth failed route=/api/v1/channels remote=203.0.113.9", lines.last())
    }
}
