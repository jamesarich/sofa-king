package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.CorsOrigin
import dev.jamesrich.sofaking.backend.admin.AdminPortal
import dev.jamesrich.sofaking.backend.admin.isPortalPath
import dev.jamesrich.sofaking.backend.admin.sameSecret
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.pairing.IssuedTokens
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.callid.generate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes

private val wireJson = Json { encodeDefaults = true }

/** Sign-in attempts at `POST /admin/login`, counted per remote address. */
internal val ADMIN_LOGIN_LIMIT = RateLimitName("admin-login")
internal const val ADMIN_LOGINS_PER_MINUTE = 10

/** How long a browser may keep a CORS preflight's answer. */
private const val CORS_MAX_AGE_SECONDS = 600L
private const val DEFLATE_PRIORITY = 0.9

/** How deep in a failure's causes the body limit's exception is looked for. */
private const val CAUSES_SEARCHED = 4

private val accessLog = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.Access")

/** Request ids, and one access log line per request: the path only, as query strings carry the output token. */
internal fun Application.installLogging() {
    install(CallId) {
        generate(length = 16, dictionary = "0123456789abcdef")
        replyToHeader(HttpHeaders.XRequestId)
    }
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
}

/**
 * Lets pages from [origins] call the API. Bearer tokens, not cookies, authorize it, so credentials stay off; the
 * portal's cookie never crosses origins.
 */
internal fun Application.installCors(origins: List<CorsOrigin>) {
    install(CORS) {
        origins.forEach { allowHost(it.host, schemes = listOf(it.scheme)) }
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.IfNoneMatch)
        allowHeader(HttpHeaders.IfMatch)
        allowHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader(HttpHeaders.XRequestId)
        maxAgeInSeconds = CORS_MAX_AGE_SECONDS
    }
}

/** The body limits, the sign-in and pairing rate limits, JSON, and compression of what is worth it. */
internal fun Application.installLimits() {
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
        // gzip first, deflate for a client that takes only it.
        gzip { priority = 1.0 }
        deflate { priority = DEFLATE_PRIORITY }
        condition { compressible(request.call.routedPath) }
    }
}

/** Every failure answers as an [ApiError]. */
internal fun Application.installStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("RESOURCE_NOT_FOUND", "Resource not found", false))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.answerFailure(cause)
        }
    }
}

private suspend fun ApplicationCall.answerFailure(cause: Throwable) {
    // The body limit fails the read, so its exception can arrive wrapped in a closed-channel one.
    if (generateSequence(cause) { it.cause }.take(CAUSES_SEARCHED).any { it is PayloadTooLargeException }) {
        val limit = requestBodyLimit(request.httpMethod, routedPath)
        respond(HttpStatusCode.PayloadTooLarge, ApiError("REQUEST_TOO_LARGE", tooLargeMessage(limit), false))
        return
    }
    when (cause) {
        is ApiFailure -> respond(cause.status, ApiError(cause.code, cause.message, cause.retryable))

        is UpstreamRejectedException -> respond(
            HttpStatusCode.BadGateway,
            ApiError("UPSTREAM_REJECTED", "Upstream address refused", false),
        )

        is BadRequestException, is SerializationException -> respond(
            HttpStatusCode.BadRequest,
            ApiError("INVALID_REQUEST", "Invalid request body", false),
        )

        // A viewer hanging up mid-response is routine; nobody is left to answer. Other failures are ours.
        else -> if (clientAbort(cause)) {
            application.log.debug("Client closed the connection ({})", cause.javaClass.simpleName)
        } else {
            // Exception messages can contain upstream signed URLs; keep them out of logs.
            application.log.error("Request failed ({})", cause.javaClass.simpleName)
            respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Request failed", true))
        }
    }
}

/**
 * Refuses a request without the token its path takes, deciding on the path routing resolves, so no spelling of a
 * guarded route skips its token. The [portal]'s pages take its sign-in; [tokens] are the ones the server issued.
 */
internal fun Application.guardTokens(
    portal: AdminPortal?,
    apiToken: String?,
    adminToken: String?,
    tokens: IssuedTokens? = null,
) {
    intercept(ApplicationCallPipeline.Call) {
        // A validator, such as the body limit, may have answered already.
        if (call.isHandled || call.refused(portal, apiToken, adminToken, tokens)) finish()
    }
}

/** Answers a request the token guard turns away, and says whether it did. */
private suspend fun ApplicationCall.refused(
    portal: AdminPortal?,
    apiToken: String?,
    adminToken: String?,
    tokens: IssuedTokens?,
): Boolean {
    val path = routedPath
    return when {
        path == null -> {
            respond(HttpStatusCode.BadRequest, ApiError("INVALID_REQUEST", "Invalid request path", false))
            true
        }

        portal != null && isPortalPath(path) -> !portal.admit(this, path)

        else -> refusedApi(path, apiToken, adminToken, tokens)
    }
}

/** The admin API's reads a `read` token opens: status, sessions, guides, the lineup, policy findings, libraries. */
private val READ_SCOPE_PATHS = listOf(
    "/api/v1/admin/status",
    "/api/v1/admin/sessions",
    "/api/v1/admin/guides",
    "/api/v1/admin/lineup",
    "/api/v1/admin/policy/preview",
    "/api/v1/admin/policy/findings",
    "/api/v1/admin/policy/changes",
    "/api/v1/admin/libraries",
)

/** The inbound hooks under `/api`, which a `hook` token opens and nothing else does but the API token. */
internal const val HOOKS_PATH = "/api/v1/hooks"

private fun under(path: String, root: String) = path == root || path.startsWith("$root/")

private suspend fun ApplicationCall.refusedApi(
    path: String,
    apiToken: String?,
    adminToken: String?,
    tokens: IssuedTokens?,
): Boolean {
    // Admin routes take the admin token when one is configured; otherwise the API token covers them too.
    // Disabling a source changes what every viewer gets, so it is one too, though outside /api/v1/admin.
    val isAdmin = under(path, "/api/v1/admin") ||
        (request.httpMethod == HttpMethod.Patch && path.startsWith("/api/v1/sources/"))
    val expected = if (isAdmin && adminToken != null) adminToken else apiToken
    val isHook = isHookPath(path)
    if (!under(path, "/api") && !isHook) return false
    val bearer = presentedToken(isHook)
    val refusal = when {
        issuedOpens(tokens, bearer, path, isAdmin, isHook) -> null

        // Without a token the API is open, so only this machine may use it: no other site's page, no rebound
        // name, no proxy.
        expected == null -> (
            HttpStatusCode.Forbidden to ApiError(
                "LOOPBACK_ONLY",
                "Without a token the API answers only this machine",
                false,
            )
            ).takeUnless { request.fromThisMachine() }

        // A pairing claim carries no token yet; the code it presents is checked by its route.
        path == PAIR_PATH || sameSecret(bearer, expected) -> null

        else -> {
            authFailed()
            HttpStatusCode.Unauthorized to ApiError(Wire.ErrorCode.AUTH_REQUIRED, "Bearer token required", false)
        }
    }
    refusal?.let { (status, error) -> respond(status, error) }
    return refusal != null
}

/**
 * Whether [bearer] is a token the server issued that opens [path]: a device's `api` token the app's routes, a `read`
 * token the admin reads in [READ_SCOPE_PATHS], a `hook` token the hooks ([isHook]). None opens the rest of the admin
 * API.
 */
private suspend fun ApplicationCall.issuedOpens(
    tokens: IssuedTokens?,
    bearer: String,
    path: String,
    isAdmin: Boolean,
    isHook: Boolean,
): Boolean {
    val token = tokens?.resolve(bearer) ?: return false
    return when (token.scope) {
        TokenScope.API -> !isAdmin && !isHook

        TokenScope.READ ->
            request.httpMethod in setOf(HttpMethod.Get, HttpMethod.Head) && READ_SCOPE_PATHS.any { under(path, it) }

        TokenScope.HOOK -> isHook
    }
}
