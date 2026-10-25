package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.CreateApiToken
import dev.jamesrich.sofaking.backend.api.CreatedApiToken
import dev.jamesrich.sofaking.backend.api.PairingCode
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.api.Viewer
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.pairing.IssuedTokens
import dev.jamesrich.sofaking.backend.store.forgetViewer
import dev.jamesrich.sofaking.backend.store.householdPreferences
import dev.jamesrich.sofaking.backend.store.saveHouseholdPreferences
import dev.jamesrich.sofaking.backend.store.saveViewerPreferences
import dev.jamesrich.sofaking.backend.store.touchViewer
import dev.jamesrich.sofaking.backend.store.viewer
import dev.jamesrich.sofaking.backend.store.viewers
import dev.jamesrich.sofaking.contract.HouseholdPreferences
import dev.jamesrich.sofaking.contract.PairingClaim
import dev.jamesrich.sofaking.contract.PairingResult
import dev.jamesrich.sofaking.contract.ViewerPreferences
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock

/** Claims at `POST /api/v1/pair`, counted per remote address; a code has six digits, so guessing must stay slow. */
internal val PAIR_LIMIT = RateLimitName("pair")
internal const val PAIR_CLAIMS_PER_MINUTE = 10

/** The one `/api/v1` path that takes no bearer token: a client claiming a pairing code has none yet. */
internal const val PAIR_PATH = "/api/v1/pair"

private val CLIENT_ID = Regex("[A-Za-z0-9._-]{1,64}")
private const val MAX_LABEL = 80
private const val MAX_LIST = 1000
private const val MAX_ID = 128

/**
 * The viewer routes: pairing (`POST /api/v1/pair`, open to anyone with a live code), each client's preferences
 * document (`GET`/`PUT /api/v1/preferences/{clientId}`, behind the API token) and the household's shared one
 * (`GET`/`PUT /api/v1/household/preferences`). A claim receives an `api` token of its
 * own, which forgetting the device revokes; a server running without [apiToken] hands out an empty token, as its API
 * answers only this machine.
 */
internal fun Route.viewerRoutes(runtime: LiveRuntime, clock: Clock, apiToken: String?) {
    rateLimit(PAIR_LIMIT) {
        post(PAIR_PATH) {
            val claim = call.appBody<PairingClaim>()
            // Checked before the claim, so a bad client id cannot spend a good code.
            val clientId = claim.clientId.takeIf { it.isNotEmpty() }?.let(::validClientId)
            if (!runtime.pairing.claim(claim.code)) {
                call.authFailed()
                throw ApiFailure(
                    HttpStatusCode.Forbidden,
                    Wire.ErrorCode.PAIRING_INVALID,
                    "That pairing code is not valid; show a new one in the admin portal",
                )
            }
            val name = label(claim.name)
            if (clientId != null) {
                runtime.store.touchViewer(clientId, name, label(claim.platform), clock.instant())
            }
            val token = if (apiToken == null) "" else runtime.tokens.pair(clientId, name)
            call.respond(PairingResult(token, runtime.store.serverId()))
        }
    }
    route("/api/v1/preferences/{clientId}") {
        get {
            val clientId = validClientId(call.parameters["clientId"]!!)
            val viewer = runtime.store.viewer(clientId)
            val preferences =
                viewer?.preferences
                    ?: throw ApiFailure(
                        HttpStatusCode.NotFound,
                        "VIEWER_NOT_FOUND",
                        "No preferences are stored for this client",
                    )
            runtime.store.touchViewer(clientId, "", "", clock.instant())
            call.respond(preferences)
        }
        put {
            val clientId = validClientId(call.parameters["clientId"]!!)
            val body = call.appBody<ViewerPreferences>()
            call.respond(runtime.store.saveViewerPreferences(clientId, validPreferences(body), clock.instant()))
        }
    }
    route("/api/v1/household/preferences") {
        get { call.respond(runtime.store.householdPreferences()) }
        put {
            val body = call.appBody<HouseholdPreferences>()
            val valid = HouseholdPreferences(ids(body.favorites, "favorites"), ids(body.hidden, "hidden"))
            call.respond(runtime.store.saveHouseholdPreferences(valid, clock.instant()))
        }
    }
}

/**
 * The admin side under `/api/v1/admin`: the viewers list, forgetting one (which revokes its token), and issuing a
 * pairing code.
 */
internal fun Route.viewerAdminRoutes(runtime: LiveRuntime) {
    get("/viewers") { call.respond(runtime.viewersWithTokens()) }
    delete("/viewers/{clientId}") {
        val clientId = validClientId(call.parameters["clientId"]!!)
        val revoked = runtime.tokens.revokeClient(clientId)
        if (!runtime.store.forgetViewer(clientId) && revoked == 0) {
            throw ApiFailure(HttpStatusCode.NotFound, "VIEWER_NOT_FOUND", "Unknown viewer")
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post("/pairing-codes") {
        val issued = runtime.pairing.issue()
        call.respond(HttpStatusCode.Created, PairingCode(issued.code, issued.expiresAt.toString()))
    }
}

/** The issued tokens under `/api/v1/admin/tokens`: list them, make a `read` or `hook` one, revoke one. */
internal fun Route.tokenAdminRoutes(tokens: IssuedTokens) {
    get("/tokens") { call.respond(tokens.all()) }
    post("/tokens") {
        val body = call.appBody<CreateApiToken>()
        val label = label(body.label)
        val refusal = when {
            label.isEmpty() -> "A token needs a label"
            body.scope == TokenScope.API -> "API tokens come from pairing; make a read or hook token"
            else -> null
        }
        if (refusal != null) throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", refusal)
        val (token, secret) = tokens.create(label, body.scope)
        call.respond(HttpStatusCode.Created, CreatedApiToken(token, secret))
    }
    delete("/tokens/{id}") {
        if (!tokens.revoke(call.parameters["id"]!!)) {
            throw ApiFailure(HttpStatusCode.NotFound, "TOKEN_NOT_FOUND", "Unknown token")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The viewers, each marked with whether it holds a token of its own. */
internal suspend fun LiveRuntime.viewersWithTokens(): List<Viewer> {
    val paired = tokens.pairedClients()
    return store.viewers().map { it.copy(ownToken = it.clientId in paired) }
}

internal fun validClientId(raw: String): String = raw.takeIf { CLIENT_ID.matches(it) }
    ?: throw ApiFailure(
        HttpStatusCode.BadRequest,
        "INVALID_REQUEST",
        "clientId must be 1 to 64 letters, digits, dots, dashes or underscores",
    )

private val whitespace = Regex("\\s+")

private fun label(raw: String): String = raw.trim().replace(whitespace, " ").take(MAX_LABEL)

private fun ids(list: List<String>, field: String): List<String> {
    if (list.size >
        MAX_LIST
    ) {
        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "$field lists more than $MAX_LIST channels")
    }
    if (list.any {
            it.isEmpty() || it.length > MAX_ID
        }
    ) {
        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "$field holds an unusable channel id")
    }
    return list.distinct()
}

/**
 * [body] trimmed to what the server stores: bounded lists of distinct ids, bounded labels; the server stamps the time.
 */
internal fun validPreferences(body: ViewerPreferences): ViewerPreferences = ViewerPreferences(
    hidden = ids(body.hidden, "hidden"),
    favorites = ids(body.favorites, "favorites"),
    recent = ids(body.recent, "recent"),
    name = label(body.name),
    platform = label(body.platform),
)
