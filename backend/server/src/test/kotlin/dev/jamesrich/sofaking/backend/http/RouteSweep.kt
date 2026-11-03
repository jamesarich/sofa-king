package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpMethod
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
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlin.test.fail

/** A runtime with every optional part on, both tokens set, and the routes its application installed. */
internal suspend fun ApplicationTestBuilder.sweepRoutes(
    apiToken: String,
    adminToken: String,
    block: suspend Routes.() -> Unit,
) {
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
            Endpoint(HttpMethod.Delete, "/api/v1/recording-rules/{x}"),
            Endpoint(HttpMethod.Put, "/api/v1/admin/policy"),
            Endpoint(HttpMethod.Post, "/api/v1/admin/libraries/{x}/scan"),
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

/** Every route with a handler as a method and a path, each parameter, optional and tailcard segment as `{x}`. */
internal fun endpoints(root: RoutingNode): List<Endpoint> = root.getAllRoutes().filter { it.hasHandler() }.map { node ->
    val chain = generateSequence(node) { it.parent }.toList().asReversed()
    var method: HttpMethod? = null
    val segments = ArrayList<String>()
    for (selector in chain.map { it.selector }) {
        when (selector) {
            is PathSegmentConstantRouteSelector -> segments += selector.value

            is PathSegmentParameterRouteSelector ->
                segments += selector.prefix.orEmpty() + "{x}" + selector.suffix.orEmpty()

            is PathSegmentOptionalParameterRouteSelector ->
                segments += selector.prefix.orEmpty() + "{x}" + selector.suffix.orEmpty()

            is PathSegmentTailcardRouteSelector -> segments += selector.prefix + "{x}"

            is PathSegmentWildcardRouteSelector -> segments += "{x}"

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

/** An installed route: its method and its path, each parameter, optional and tailcard segment written `{x}`. */
internal data class Endpoint(val method: HttpMethod, val path: String) {
    override fun toString() = "${method.value} $path"
}

internal class Routes(val endpoints: List<Endpoint>, val browser: HttpClient, val runtime: LiveRuntime)
