package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.admin.AdminPortal
import dev.jamesrich.sofaking.backend.admin.PlexStatusSource
import dev.jamesrich.sofaking.backend.store.IdentityStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.cio.ChannelIOException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ConnectionClosedException
import java.time.Clock

/**
 * Every request body is capped at [CONTROL_BODY_LIMIT], except the policy writes, which may carry a whole policy file.
 */
internal fun requestBodyLimit(method: HttpMethod, path: String?): Long = when {
    method == HttpMethod.Put && path == "/api/v1/admin/policy" -> POLICY_BODY_LIMIT
    method == HttpMethod.Post && (path == "/admin/providers/edit" || path == "/admin/groups/edit") -> POLICY_BODY_LIMIT
    else -> CONTROL_BODY_LIMIT
}

/**
 * Whether a request to the routed [path] gets an access log line: not media, logos, artwork,
 * player streams or health checks.
 */
internal fun accessLogged(path: String?): Boolean = path == null || !(
    path.startsWith("/media/") || path.startsWith("/logos/") || path.startsWith("/artwork/") ||
        path.startsWith("/output/stream/") ||
        path.startsWith("/auto/") ||
        path == "/healthz" || path == "/readyz"
    )

fun Application.backendModule(
    store: IdentityStore,
    clock: Clock,
    runtime: LiveRuntime? = null,
    apiToken: String? = null,
    adminToken: String? = null,
    outputs: OutputConfig = OutputConfig(),
    hdhr: HdhrConfig = HdhrConfig(),
    plexStatus: PlexStatusSource? = null,
    /** The settings the server started with, which the portal's settings page shows with every token redacted. */
    config: ServerConfig? = null,
) {
    // The portal signs in with the admin token, or the API token when no admin token is set.
    val portal = runtime?.let { AdminPortal(it, adminToken ?: apiToken, outputs, hdhr, clock, plexStatus, config) }
    attributes.computeIfAbsent(AuthFailureLogKey) { AuthFailureLog(clock) }
    // The path routing resolves, decoded once per request; the plugins below and the token guard read it from the call.
    intercept(ApplicationCallPipeline.Setup) {
        routedPath(call.request.path())?.let { call.attributes.put(RoutedPathKey, it) }
    }
    installLogging()
    config?.corsOrigins?.takeIf { it.isNotEmpty() }?.let { installCors(it) }
    installLimits()
    installStatusPages()
    guardTokens(portal, apiToken, adminToken)
    routing {
        healthRoutes(store, clock, runtime)
        if (runtime != null) {
            liveRoutes(runtime)
            adminRoutes(runtime, clock, config?.revision ?: "unknown", hdhr.enabled)
            viewerRoutes(runtime, clock, apiToken)
            runtime.dvr?.let { recordingRoutes(it) }
            playbackRoutes(runtime)
            outputRoutes(runtime, clock, outputs, apiToken)
            logoRoutes(runtime.logos)
            runtime.libraries?.let {
                libraryRoutes(it)
                artworkRoutes(it)
            }
            runtime.vod?.let { vodRoutes(it) }
            if (hdhr.enabled) hdhrRoutes(runtime, hdhr, store)
            portal?.install(this)
        }
    }
}

/**
 * The path routing matches: empty segments dropped, each segment percent-decoded. Null when a segment is undecodable,
 * decodes to a dot segment, or decodes to something containing '/', which routing would otherwise see as a path split,
 * or a control character, which would forge lines in the access log.
 */
internal fun routedPath(raw: String): String? {
    val segments = raw.split('/').filter { it.isNotEmpty() }.map { segment ->
        runCatching { segment.decodeURLPart() }.getOrNull()
            ?: return null
    }
    if (segments.any { it in DOT_SEGMENTS || it.contains('/') || it.any(Char::isISOControl) }) return null
    return segments.joinToString("/", prefix = "/")
}

private val DOT_SEGMENTS = setOf(".", "..")

internal val RoutedPathKey = AttributeKey<String>("RoutedPath")

/**
 * Whether a request reached the tokenless API from this machine itself: a loopback `Host`, so a rebound DNS name is
 * refused; no proxy's forwarding header; and no `Origin` but a loopback one, so another site's page cannot send it.
 */
internal fun ApplicationRequest.fromThisMachine(): Boolean {
    // The Host header's name, or the local address when a client sent none; browsers always send one.
    val host =
        headers[HttpHeaders.Host]?.let { if (it.startsWith("[")) it.substringBefore(']') else it.substringBefore(':') }
            ?: local.serverHost
    if (!isLoopbackName(host)) return false
    if (FORWARDING_HEADERS.any { headers[it] != null }) return false
    val origin = headers[HttpHeaders.Origin] ?: return true
    return runCatching { java.net.URI(origin).host }.getOrNull()?.let(::isLoopbackName) == true
}

private val FORWARDING_HEADERS =
    listOf(HttpHeaders.Forwarded, HttpHeaders.XForwardedFor, HttpHeaders.XForwardedHost, "X-Real-IP")
private val LOOPBACK_V4 = Regex("127(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}")

internal fun isLoopbackName(raw: String): Boolean {
    val host = raw.trim('[', ']').lowercase().removeSuffix(".")
    return host == "localhost" || host.endsWith(".localhost") || host == "::1" || LOOPBACK_V4.matches(host)
}

/** The path routing resolves for this call, computed once at setup; null when [routedPath] refused the raw path. */
internal val ApplicationCall.routedPath: String? get() = attributes.getOrNull(RoutedPathKey)

/** Responses worth compressing: API documents, the portal, the playlist and the guide. Media and logos never are. */
internal fun compressible(path: String?): Boolean = path != null && (
    path.startsWith("/api/") || path == "/admin" || path.startsWith("/admin/") ||
        path == "/output/playlist.m3u" || path == "/output/guide.xml" || path == "/lineup.json"
    )

/** A write or read that failed because the client's connection is gone, as Ktor, Netty and the JDK each report it. */
internal fun clientAbort(cause: Throwable): Boolean {
    val causes = generateSequence(cause) { it.cause }.take(4)
    return causes.any { error ->
        error is ClosedByteChannelException || error is ConnectionClosedException || error is ChannelIOException ||
            error is java.nio.channels.ClosedChannelException ||
            (error is java.io.IOException && error.message?.let { ioAbortMessage.containsMatchIn(it) } == true)
    }
}
private val ioAbortMessage = Regex("(?i)broken pipe|connection reset|connection or inbound has closed")
