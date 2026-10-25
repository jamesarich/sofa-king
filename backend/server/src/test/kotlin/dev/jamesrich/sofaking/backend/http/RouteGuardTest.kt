package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.admin.isPortalPath
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.append
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentRegexRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.method
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sweeps every route the application installs, read from its routing tree so a new route is swept too, against the
 * guard in `backendModule` and the portal's: the API token on `/api`, the admin token on `/api/v1/admin`, and the
 * session and its CSRF token on the portal's POSTs.
 */
class RouteGuardTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiToken = "api".repeat(12)
    private val adminToken = "admin".repeat(8)

    private data class Endpoint(val method: HttpMethod, val path: String) {
        override fun toString() = "${method.value} $path"
    }

    private class Routes(val endpoints: List<Endpoint>, val browser: HttpClient, val runtime: LiveRuntime)

    /** A runtime with every optional part on, both tokens set, and the routes its application installed. */
    private suspend fun ApplicationTestBuilder.routes(block: suspend Routes.() -> Unit) {
        val dir = Files.createTempDirectory("sofa-route-guard-")
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config =
            ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                dir.resolve("sources.json"),
                apiToken,
                adminToken = adminToken,
                updateImage = null,
                comskip = null,
            )
        val store = openStore(dir)
        val clock = MutableClock()
        val runtime = LiveRuntime.create(store, config, clock, probes = false, restart = {})
        try {
            lateinit var app: Application
            application {
                backendModule(store, clock, runtime, apiToken, adminToken, config.outputs, config.hdhr, config = config)
                app =
                    this
            }
            startApplication()
            val endpoints = endpoints(app.plugin(RoutingRoot))
            // The sweep reads the tree it means to: these are routes of several route files.
            for (known in listOf(
                Endpoint(HttpMethod.Post, PAIR_PATH),
                Endpoint(HttpMethod.Get, "/api/v1/channels"),
                Endpoint(HttpMethod.Post, "/api/v1/recordings"),
                Endpoint(HttpMethod.Delete, "/api/v1/recording-rules/x"),
                Endpoint(HttpMethod.Put, "/api/v1/admin/policy"),
                Endpoint(HttpMethod.Post, "/api/v1/admin/libraries/x/scan"),
                Endpoint(HttpMethod.Post, "/api/v1/hooks/servarr"),
                Endpoint(HttpMethod.Post, "/Library/Media/Updated"),
                Endpoint(HttpMethod.Post, "/admin/login"),
                Endpoint(HttpMethod.Post, "/admin/logout"),
            )) {
                assertTrue(known in endpoints, "the sweep found no $known in $endpoints")
            }
            Routes(
                endpoints,
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                },
                runtime,
            ).block()
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    /** Every route with a handler as a method and a concrete path, each parameter segment filled with `x`. */
    private fun endpoints(root: RoutingNode): List<Endpoint> =
        root.getAllRoutes().filter { it.hasHandler() }.map { node ->
            val chain = generateSequence(node) { it.parent }.toList().asReversed()
            var method: HttpMethod? = null
            val segments = ArrayList<String>()
            for (selector in chain.map { it.selector }) {
                when (selector) {
                    is PathSegmentConstantRouteSelector -> segments += selector.value

                    is PathSegmentParameterRouteSelector ->
                        segments +=
                            selector.prefix.orEmpty() + "x" + selector.suffix.orEmpty()

                    is PathSegmentOptionalParameterRouteSelector ->
                        segments +=
                            selector.prefix.orEmpty() + "x" + selector.suffix.orEmpty()

                    is PathSegmentTailcardRouteSelector -> segments += selector.prefix + "x"

                    is PathSegmentWildcardRouteSelector -> segments += "x"

                    is PathSegmentRegexRouteSelector -> fail(
                        "the sweep cannot fill the regex route under ${node.parent}",
                    )

                    is HttpMethodRouteSelector -> method = selector.method

                    // The root, rate limits and the like add no path segment.
                    else -> {}
                }
            }
            Endpoint(method ?: fail("the route $node has no method"), segments.joinToString("/", prefix = "/"))
        }.distinct()

    private fun Endpoint.isApi() = path == "/api" || path.startsWith("/api/")
    private fun Endpoint.isAdminApi() = path == "/api/v1/admin" || path.startsWith("/api/v1/admin/") ||
        (method == HttpMethod.Patch && path.startsWith("/api/v1/sources/"))

    private fun Endpoint.isHook() = isHookPath(path)

    /** Whether a read token opens this route: a GET of the admin reads a dashboard needs. */
    private fun Endpoint.readScope() = method == HttpMethod.Get && listOf(
        "/api/v1/admin/status",
        "/api/v1/admin/sessions",
        "/api/v1/admin/guides",
        "/api/v1/admin/lineup",
        "/api/v1/admin/policy/preview",
        "/api/v1/admin/policy/findings",
        "/api/v1/admin/policy/changes",
        "/api/v1/admin/libraries",
    ).any { path == it || path.startsWith("$it/") }

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

    @Test fun `every admin API route answers 401 with the API token, and every other API route with the admin token`() =
        testApplication {
            routes {
                val admin = endpoints.filter { it.isAdminApi() }
                assertTrue(admin.size > 20, "only ${admin.size} admin routes")
                val violations = buildList {
                    for (endpoint in admin) {
                        refused(call(endpoint, apiToken))?.let {
                            add("$endpoint with the API token: $it")
                        }
                    }
                    for (endpoint in endpoints.filter { it.isApi() && !it.isAdminApi() && it.path != PAIR_PATH }) {
                        refused(call(endpoint, adminToken))?.let { add("$endpoint with the admin token: $it") }
                    }
                }
                assertTrue(violations.isEmpty(), violations.joinToString("\n"))
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

    @Test fun `a device token opens every other API route, and a read or hook token none`() = testApplication {
        routes {
            val device = runtime.tokens.pair("tv-1", "TV")
            val read = runtime.tokens.create("Homepage", TokenScope.READ).second
            val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
            val violations = buildList {
                val app = endpoints.filter { it.isApi() && !it.isAdminApi() && !it.isHook() && it.path != PAIR_PATH }
                for (endpoint in app) {
                    if (refused(call(endpoint, device)) == null) add("$endpoint refused a device token")
                    refused(call(endpoint, read))?.let { add("$endpoint with a read token: $it") }
                    refused(call(endpoint, hook))?.let { add("$endpoint with a hook token: $it") }
                }
            }
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    @Test fun `every hook opens to a hook token or the API token and to no other`() = testApplication {
        routes {
            val device = runtime.tokens.pair("tv-1", "TV")
            val read = runtime.tokens.create("Homepage", TokenScope.READ).second
            val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
            val hooks = endpoints.filter { it.isHook() }
            assertTrue(hooks.size >= 13, "only ${hooks.size} hooks")
            val violations = buildList {
                for (endpoint in hooks) {
                    refused(call(endpoint))?.let { add("$endpoint without a token: $it") }
                    for ((name, wrong) in listOf("device" to device, "read" to read, "admin" to adminToken)) {
                        refused(call(endpoint, wrong))?.let { add("$endpoint with the $name token: $it") }
                    }
                    if (refused(call(endpoint, hook)) == null) add("$endpoint refused a hook token")
                    if (refused(call(endpoint, apiToken)) == null) add("$endpoint refused the API token")
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
