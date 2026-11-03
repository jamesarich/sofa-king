package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.admin.isPortalPath
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.dvr.StoredRecording
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.append
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.Application
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.method
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sweeps every route the application installs, read from its routing tree so a new route is swept too, against the
 * guard in `backendModule` and the portal's: the API token on `/api`, the admin token on `/api/v1/admin`, and the
 * session and its CSRF token on the portal's POSTs.
 */
class RouteGuardTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiToken = "api".repeat(12)
    private val adminToken = "admin".repeat(8)

    /** The sweep's runtime and routes, each `{x}` segment filled with `x` for the requests below. */
    private suspend fun ApplicationTestBuilder.routes(block: suspend Routes.() -> Unit) =
        sweepRoutes(apiToken, adminToken) {
            Routes(endpoints.map { it.copy(path = it.path.replace("{x}", "x")) }, browser, runtime).block()
        }

    private fun Endpoint.isApi() = path == "/api" || path.startsWith("/api/")
    private fun Endpoint.isAdminApi() =
        path == "/api/v1/admin" || path.startsWith("/api/v1/admin/") || path == MCP_PATH ||
            (method == HttpMethod.Patch && path.startsWith("/api/v1/sources/"))

    private fun Endpoint.isHook() = isHookPath(path)

    /**
     * Whether a read token opens this route: a GET of the summary and the admin reads a dashboard needs, and the MCP
     * endpoint, which lists it only the read tools.
     */
    private fun Endpoint.readScope() = path == MCP_PATH || (method == HttpMethod.Get && readPaths.any { under(it) })

    private fun Endpoint.under(root: String) = path == root || path.startsWith("$root/")

    private val readPaths = listOf(
        "/api/v1/status",
        "/api/v1/admin/status",
        "/api/v1/admin/sessions",
        "/api/v1/admin/guides",
        "/api/v1/admin/lineup",
        "/api/v1/admin/policy/preview",
        "/api/v1/admin/policy/findings",
        "/api/v1/admin/policy/changes",
        "/api/v1/admin/libraries",
        "/api/v1/admin/diagnostics/crashes",
    )

    private suspend fun ApplicationTestBuilder.call(endpoint: Endpoint, token: String? = null): HttpResponse =
        client.request(endpoint.path) {
            method = endpoint.method
            token?.let { bearerAuth(it) }
        }

    /** Null when [response] is the guard's 401, else what it was. */
    private suspend fun refused(response: HttpResponse): String? {
        if (response.status != HttpStatusCode.Unauthorized) return "${response.status.value}"
        val code = runCatching { json.decodeFromString<ApiError>(response.bodyAsText()).code }.getOrNull()
        return if (code == Wire.ErrorCode.AUTH_REQUIRED) null else "401 with code $code"
    }

    @Test fun `every API route but pairing answers 401 without the API token or with a wrong one`() = testApplication {
        routes {
            val api = endpoints.filter { it.isApi() }
            assertTrue(api.size > 50, "only ${api.size} API routes")
            val violations = buildList {
                for (endpoint in api) {
                    if (endpoint.path == PAIR_PATH) {
                        val status = call(endpoint).status
                        if (status == HttpStatusCode.Unauthorized) {
                            add("$endpoint: pairing answered 401 without a token")
                        }
                        continue
                    }
                    refused(call(endpoint))?.let { add("$endpoint without a token: $it") }
                    refused(call(endpoint, apiToken.reversed()))?.let { add("$endpoint with a wrong token: $it") }
                }
            }
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test fun `every admin API route answers 401 with the API token, and every API route opens to the admin token`() =
        testApplication {
            routes {
                val admin = endpoints.filter { it.isAdminApi() }
                assertTrue(admin.size > 20, "only ${admin.size} admin routes")
                val violations = buildList {
                    for (endpoint in admin) {
                        refused(call(endpoint, apiToken))?.let { add("$endpoint with the API token: $it") }
                    }
                    // The admin routes run for the admin token in their own tests; the sweep would run them all here.
                    for (endpoint in endpoints.filter { it.isApi() && !it.isAdminApi() && it.path != PAIR_PATH }) {
                        if (refused(call(endpoint, adminToken)) == null) add("$endpoint refused the admin token")
                    }
                }
                assertTrue(violations.isEmpty(), violations.joinToString("\n"))
            }
        }

    @Test fun `the admin token reads the viewer routes, a wrong token and none do not, a device token still does`() =
        testApplication {
            routes {
                val device = runtime.tokens.pair("tv-1", "TV")
                val start = Instant.parse("2026-10-09T13:00:00Z")
                runtime.store.addRecording(
                    StoredRecording(
                        "done", null, "channel", "Channel", start, start.plusSeconds(1800),
                        Duration.ofSeconds(60), Duration.ofSeconds(120), "Show",
                        state = Wire.RecordingState.COMPLETED, path = "Show/Show.ts", bytes = 8, createdAt = start,
                        updatedAt = start, commercials = Wire.CommercialScan.DONE,
                        breaks = listOf(RecordingBreak(1_000, 2_500)),
                    ),
                )
                val viewerReads = listOf(
                    "/api/v1/channels",
                    "/api/v1/recordings",
                    "/api/v1/recordings/done",
                    "/api/v1/guide/on-later?kind=movie",
                    "/api/v1/household/preferences",
                    "/api/v1/status",
                )
                for (path in viewerReads) {
                    for ((name, token) in listOf("admin" to adminToken, "device" to device)) {
                        val status = client.get(path) { bearerAuth(token) }.status
                        assertEquals(HttpStatusCode.OK, status, "GET $path with the $name token")
                    }
                    for (token in listOf(null, apiToken.reversed(), adminToken.reversed())) {
                        val status = client.get(path) { token?.let { bearerAuth(it) } }.status
                        assertEquals(HttpStatusCode.Unauthorized, status, "GET $path with ${token ?: "no token"}")
                    }
                }
                // A session route past the guard answers for the session, not for the token.
                val session = client.get("/api/v1/playback-sessions/x") { bearerAuth(adminToken) }
                assertEquals(HttpStatusCode.Gone, session.status)
                val body = client.get("/api/v1/recordings/done") { bearerAuth(adminToken) }.bodyAsText()
                assertTrue(body.contains(Wire.CommercialScan.DONE), body)
            }
        }

    @Test fun `the admin token writes any viewer, a token paired to a device only its own`() = testApplication {
        routes {
            val device = runtime.tokens.pair("tv-1", "TV")
            suspend fun put(clientId: String, token: String) = client.put("/api/v1/preferences/$clientId") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"favorites":["a"]}""")
            }.status
            assertEquals(HttpStatusCode.OK, put("tv-1", adminToken))
            assertEquals(HttpStatusCode.OK, put("tv-2", adminToken))
            assertEquals(HttpStatusCode.OK, put("tv-1", device))
            assertEquals(HttpStatusCode.Forbidden, put("tv-2", device))
            // Watch state belongs to the household, so the admin token saves it as any device does.
            val start = Instant.parse("2026-10-09T13:00:00Z")
            runtime.store.addRecording(
                StoredRecording(
                    "done", null, "channel", "Channel", start, start.plusSeconds(1800),
                    Duration.ofSeconds(60), Duration.ofSeconds(120), "Show",
                    state = Wire.RecordingState.COMPLETED, path = "Show/Show.ts", bytes = 8, createdAt = start,
                    updatedAt = start,
                ),
            )
            val patch = client.patch("/api/v1/recordings/done") {
                bearerAuth(adminToken)
                contentType(ContentType.Application.Json)
                setBody("""{"watched":true}""")
            }
            assertEquals(HttpStatusCode.OK, patch.status)
            assertTrue(patch.bodyAsText().contains("\"watched\":true"))
        }
    }

    @Test fun `issued tokens open no admin route but the reads a read token is for`() = testApplication {
        routes {
            val device = runtime.tokens.pair("tv-1", "TV")
            val read = runtime.tokens.create("Homepage", TokenScope.READ).second
            val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
            val violations = buildList {
                for (endpoint in endpoints.filter { it.isAdminApi() }) {
                    refused(call(endpoint, device))?.let { add("$endpoint with a device token: $it") }
                    refused(call(endpoint, hook))?.let { add("$endpoint with a hook token: $it") }
                    val readRefusal = refused(call(endpoint, read))
                    if (endpoint.readScope() == (readRefusal == null)) {
                        add("$endpoint with a read token: ${readRefusal ?: "401"}")
                    }
                }
            }
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test fun `a device token opens every other API route, a read token only the summary, a hook token none`() =
        testApplication {
            routes {
                val device = runtime.tokens.pair("tv-1", "TV")
                val read = runtime.tokens.create("Homepage", TokenScope.READ).second
                val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
                val violations = buildList {
                    val app =
                        endpoints.filter { it.isApi() && !it.isAdminApi() && !it.isHook() && it.path != PAIR_PATH }
                    for (endpoint in app) {
                        if (refused(call(endpoint, device)) == null) add("$endpoint refused a device token")
                        val readRefusal = refused(call(endpoint, read))
                        if (endpoint.readScope() == (readRefusal == null)) {
                            add("$endpoint with a read token: ${readRefusal ?: "401"}")
                        }
                        refused(call(endpoint, hook))?.let { add("$endpoint with a hook token: $it") }
                    }
                }
                assertTrue(violations.isEmpty(), violations.joinToString("\n"))
            }
        }

    @Test fun `every hook opens to a hook, API or admin token and to no other`() = testApplication {
        routes {
            val device = runtime.tokens.pair("tv-1", "TV")
            val read = runtime.tokens.create("Homepage", TokenScope.READ).second
            val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
            val hooks = endpoints.filter { it.isHook() }
            assertTrue(hooks.size >= 13, "only ${hooks.size} hooks")
            val violations = buildList {
                for (endpoint in hooks) {
                    refused(call(endpoint))?.let { add("$endpoint without a token: $it") }
                    for ((name, wrong) in listOf("device" to device, "read" to read)) {
                        refused(call(endpoint, wrong))?.let { add("$endpoint with the $name token: $it") }
                    }
                    if (refused(call(endpoint, hook)) == null) add("$endpoint refused a hook token")
                    if (refused(call(endpoint, apiToken)) == null) add("$endpoint refused the API token")
                    if (refused(call(endpoint, adminToken)) == null) add("$endpoint refused the admin token")
                }
            }
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test fun `every portal POST but sign-in answers 401 without a session and 403 without its CSRF token`() =
        testApplication {
            routes {
                val posts = endpoints.filter {
                    it.method == HttpMethod.Post && isPortalPath(it.path) && it.path != "/admin/login"
                }
                assertTrue(posts.size > 20, "only ${posts.size} portal POSTs")
                val violations = ArrayList<String>()
                for (endpoint in posts) {
                    val status = client.post(endpoint.path).status
                    if (status != HttpStatusCode.Unauthorized) {
                        violations += "$endpoint without a session: ${status.value}"
                    }
                }
                assertEquals(
                    HttpStatusCode.SeeOther,
                    browser.submitForm(
                        "/admin/login",
                        parameters {
                            append("token", adminToken)
                        },
                    ).status,
                )
                for (endpoint in posts) {
                    val bare = browser.post(endpoint.path).status
                    if (bare != HttpStatusCode.Forbidden) violations += "$endpoint without a CSRF token: ${bare.value}"
                    val wrong = browser.submitForm(endpoint.path, parameters { append("csrf", "not-the-token") }).status
                    if (wrong != HttpStatusCode.Forbidden) {
                        violations += "$endpoint with a wrong CSRF token: ${wrong.value}"
                    }
                }
                assertTrue(violations.isEmpty(), violations.joinToString("\n"))
                // The session survived the sweep, so each 403 above was the CSRF check, not a lost session.
                assertEquals(HttpStatusCode.OK, browser.get("/admin/lineup").status)
            }
        }
}
