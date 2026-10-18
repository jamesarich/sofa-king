package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.admin.AdminPortal
import dev.jamesrich.sofaking.backend.admin.PlexStatusSource
import dev.jamesrich.sofaking.backend.admin.isPortalPath
import dev.jamesrich.sofaking.backend.admin.sameSecret
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.store.IdentityStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import io.ktor.util.cio.ChannelIOException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ConnectionClosedException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.callid.generate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import java.time.Clock
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

private val wireJson = Json { encodeDefaults = true }

/** Sign-in attempts at `POST /admin/login`, counted per remote address. */
internal val ADMIN_LOGIN_LIMIT = RateLimitName("admin-login")
internal const val ADMIN_LOGINS_PER_MINUTE = 10

/** Every request body is capped at [CONTROL_BODY_LIMIT], except the policy writes, which may carry a whole policy file. */
internal fun requestBodyLimit(method: HttpMethod, path: String?): Long = when {
    method == HttpMethod.Put && path == "/api/v1/admin/policy" -> POLICY_BODY_LIMIT
    method == HttpMethod.Post && (path == "/admin/providers/edit" || path == "/admin/groups/edit") -> POLICY_BODY_LIMIT
    else -> CONTROL_BODY_LIMIT
}

private val accessLog = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.Access")

/** Whether a request to the routed [path] gets an access log line: not media, logos, artwork, player streams or health checks. */
internal fun accessLogged(path: String?): Boolean = path == null || !(
    path.startsWith("/media/") || path.startsWith("/logos/") || path.startsWith("/artwork/") || path.startsWith("/output/stream/") || path.startsWith("/auto/") ||
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
    intercept(ApplicationCallPipeline.Setup) { routedPath(call.request.path())?.let { call.attributes.put(RoutedPathKey, it) } }
    install(CallId) {
        generate(length = 16, dictionary = "0123456789abcdef")
        replyToHeader(HttpHeaders.XRequestId)
    }
    // The path only: query strings carry the output token.
    install(CallLogging) {
        level = Level.INFO
        logger = accessLog
        callIdMdc("requestId")
        filter { call -> accessLogged(call.routedPath) }
        format { call ->
            "request method=${call.request.httpMethod.value} path=${call.routedPath ?: "-"} " +
                "status=${call.response.status()?.value ?: "-"} durationMs=${call.processingTimeMillis()}"
        }
    }
    // Bearer tokens, not cookies, authorize the API, so credentials stay off; the portal's cookie never crosses origins.
    config?.corsOrigins?.takeIf { it.isNotEmpty() }?.let { origins ->
        install(CORS) {
            origins.forEach { allowHost(it.host, schemes = listOf(it.scheme)) }
            allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Patch); allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.IfNoneMatch); allowHeader(HttpHeaders.IfMatch); allowHeader(HttpHeaders.XRequestId)
            exposeHeader(HttpHeaders.ETag); exposeHeader(HttpHeaders.XRequestId)
            maxAgeInSeconds = 600
        }
    }
    install(RequestBodyLimit) { bodyLimit { call -> requestBodyLimit(call.request.httpMethod, call.routedPath) } }
    install(RateLimit) {
        register(ADMIN_LOGIN_LIMIT) {
            rateLimiter(limit = ADMIN_LOGINS_PER_MINUTE, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(PAIR_LIMIT) {
            rateLimiter(limit = PAIR_CLAIMS_PER_MINUTE, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    install(ContentNegotiation) { json(wireJson) }
    install(Compression) {
        gzip { priority = 1.0 }
        deflate { priority = 0.9 }
        condition { compressible(request.call.routedPath) }
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ApiError("RESOURCE_NOT_FOUND", "Resource not found", false)) }
        exception<Throwable> { call, cause ->
            // The body limit fails the read, so its exception can arrive wrapped in a closed-channel one.
            if (cause !is CancellationException && generateSequence(cause) { it.cause }.take(4).any { it is PayloadTooLargeException }) {
                val limit = requestBodyLimit(call.request.httpMethod, call.routedPath)
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("REQUEST_TOO_LARGE", tooLargeMessage(limit), false))
                return@exception
            }
            when (cause) {
                is CancellationException -> throw cause
                is ApiFailure -> call.respond(HttpStatusCode.fromValue(cause.status), ApiError(cause.code, cause.message, cause.retryable))
                is UpstreamRejectedException -> call.respond(HttpStatusCode.BadGateway, ApiError("UPSTREAM_REJECTED", "Upstream address refused", false))
                is BadRequestException, is kotlinx.serialization.SerializationException -> call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REQUEST", "Invalid request body", false))
                // A viewer hanging up mid-response is routine; nobody is left to answer. Other failures are ours.
                else -> if (clientAbort(cause)) {
                    call.application.log.debug("Client closed the connection ({})", cause.javaClass.simpleName)
                } else {
                    // Exception messages can contain upstream signed URLs; keep them out of logs.
                    call.application.log.error("Request failed ({})", cause.javaClass.simpleName)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Request failed", true))
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Call) {
        // A validator, such as the body limit, may have answered already.
        if (call.isHandled) { finish(); return@intercept }
        // Decide on the path routing resolves, so no spelling of a guarded route skips its token.
        val path = call.routedPath
        if (path == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REQUEST", "Invalid request path", false))
            finish()
        } else if (portal != null && isPortalPath(path)) {
            if (!portal.admit(call, path)) finish()
        } else {
            // Admin routes take the admin token when one is configured; otherwise the API token covers them too.
            val isAdmin = path == "/api/v1/admin" || path.startsWith("/api/v1/admin/")
            val expected = if (isAdmin && adminToken != null) adminToken else apiToken
            // A pairing claim carries no token yet; the code it presents is checked by its route.
            if ((path == "/api" || path.startsWith("/api/")) && expected != null && path != PAIR_PATH) {
                val presented = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
                if (!sameSecret(presented, expected)) {
                    call.authFailed()
                    call.respond(HttpStatusCode.Unauthorized, ApiError(Wire.ErrorCode.AUTH_REQUIRED, "Bearer token required", false))
                    finish()
                }
            }
        }
    }
    routing {
        get("/healthz") { call.respondText("ok") }
        get("/readyz") {
            if (store.ready()) call.respondText("ready")
            else call.respond(HttpStatusCode.ServiceUnavailable, ApiError("NOT_READY", "Storage unavailable", true))
        }
        get("/api/v1/bootstrap") {
            if (!store.ready()) call.respond(HttpStatusCode.ServiceUnavailable, ApiError("NOT_READY", "Storage unavailable", true))
            else call.respond(Bootstrap(serverId = store.serverId(), serverTime = clock.instant().toString(), capabilities = Capabilities(catalog = runtime != null, guide = runtime != null, playback = runtime != null, viewers = runtime != null, library = runtime?.libraries != null)))
        }
        if (runtime != null) {
            get("/api/v1/channels") {
                // The revision is cheap; the body is not, so answer a matching If-None-Match before building it.
                // Logos are server-relative, so a client behind any proxy resolves them on its own base URL.
                val revision = runtime.catalog.catalogRevision()
                val etag = "\"${hash(revision)}\""
                call.response.header(HttpHeaders.ETag, etag)
                if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) call.respond(HttpStatusCode.NotModified)
                else {
                    val catalog = runtime.catalog.catalog(revision)
                    call.respond(catalog.copy(channels = catalog.channels.map { channel -> channel.copy(logo = channel.logo?.let { runtime.logos.publicUrl("", it) }) }))
                }
            }
            get("/api/v1/providers") { call.respond(runtime.feeds.providers()) }
            get("/api/v1/sources") { call.respond(runtime.feeds.sources()) }
            patch("/api/v1/sources/{id}") {
                val source = runtime.feeds.updateSource(call.parameters["id"]!!, call.body<UpdateSource>())
                runtime.sessions.healthChanged(source.id)
                call.respond(source)
            }
            post("/api/v1/guide/query") { call.respond(runtime.catalog.guide(call.appBody<GuideQuery>())) }
            adminRoutes(runtime, clock, config?.revision ?: "unknown")
            viewerRoutes(runtime, clock, apiToken)
            post("/api/v1/playback-sessions") { call.respond(HttpStatusCode.Created, runtime.sessions.open(call.appBody<OpenPlayback>(), call.request.origin.remoteAddress)) }
            get("/api/v1/playback-sessions/{id}") { call.respond(runtime.sessions.get(call.parameters["id"]!!)) }
            patch("/api/v1/playback-sessions/{id}") { call.respond(runtime.sessions.update(call.parameters["id"]!!, call.appBody<UpdatePlayback>())) }
            delete("/api/v1/playback-sessions/{id}") { runtime.sessions.release(call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
            get("/media/sessions/{id}/{epoch}/{token}") {
                val epoch = call.parameters["epoch"]?.toIntOrNull() ?: throw ApiFailure(400, "INVALID_REQUEST", "Invalid stream epoch")
                runtime.relay.serve(call, call.parameters["id"]!!, epoch, call.parameters["token"]!!)
            }
            outputRoutes(runtime, clock, outputs, apiToken)
            logoRoutes(runtime.logos)
            runtime.libraries?.let { libraryRoutes(it); artworkRoutes(it) }
            if (hdhr.enabled) hdhrRoutes(runtime, hdhr, store)
            portal?.install(this)
        }
    }
}
/**
 * The path routing matches: empty segments dropped, each segment percent-decoded. Null when a segment is undecodable,
 * decodes to a dot segment, or decodes to something containing '/', which routing would otherwise see as a path split.
 */
internal fun routedPath(raw: String): String? {
    val segments = raw.split('/').filter { it.isNotEmpty() }.map { segment -> runCatching { segment.decodeURLPart() }.getOrNull() ?: return null }
    if (segments.any { it == "." || it == ".." || it.contains('/') }) return null
    return segments.joinToString("/", prefix = "/")
}

internal val RoutedPathKey = AttributeKey<String>("RoutedPath")

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
