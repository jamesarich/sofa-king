package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.PairingCode
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.forgetViewer
import dev.jamesrich.sofaking.backend.store.saveViewerPreferences
import dev.jamesrich.sofaking.backend.store.touchViewer
import dev.jamesrich.sofaking.backend.store.viewer
import dev.jamesrich.sofaking.backend.store.viewers
import dev.jamesrich.sofaking.contract.PairingClaim
import dev.jamesrich.sofaking.contract.PairingResult
import dev.jamesrich.sofaking.contract.ViewerPreferences
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.*
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
 * The viewer routes: pairing (`POST /api/v1/pair`, open to anyone with a live code) and each client's preferences
 * document (`GET`/`PUT /api/v1/preferences/{clientId}`, behind the API token). [apiToken] is what a claim receives;
 * a server running without one hands out an empty token.
 */
internal fun Route.viewerRoutes(runtime: LiveRuntime, clock: Clock, apiToken: String?) {
    rateLimit(PAIR_LIMIT) {
        post(PAIR_PATH) {
            val claim = call.appBody<PairingClaim>()
            // Checked before the claim, so a bad client id cannot spend a good code.
            val clientId = claim.clientId.takeIf { it.isNotEmpty() }?.let(::validClientId)
            if (!runtime.pairing.claim(claim.code)) {
                call.authFailed()
                throw ApiFailure(403, Wire.ErrorCode.PAIRING_INVALID, "That pairing code is not valid; show a new one in the admin portal")
            }
            if (clientId != null) runtime.store.touchViewer(clientId, label(claim.name), label(claim.platform), clock.instant())
            call.respond(PairingResult(apiToken.orEmpty(), runtime.store.serverId()))
        }
    }
    route("/api/v1/preferences/{clientId}") {
        get {
            val clientId = validClientId(call.parameters["clientId"]!!)
            val viewer = runtime.store.viewer(clientId)
            val preferences = viewer?.preferences ?: throw ApiFailure(404, "VIEWER_NOT_FOUND", "No preferences are stored for this client")
            runtime.store.touchViewer(clientId, "", "", clock.instant())
            call.respond(preferences)
        }
        put {
            val clientId = validClientId(call.parameters["clientId"]!!)
            val body = call.appBody<ViewerPreferences>()
            call.respond(runtime.store.saveViewerPreferences(clientId, validPreferences(body), clock.instant()))
        }
    }
}

/** The admin side under `/api/v1/admin`: the viewers list, forgetting one, and issuing a pairing code. */
internal fun Route.viewerAdminRoutes(runtime: LiveRuntime) {
    get("/viewers") { call.respond(runtime.store.viewers()) }
    delete("/viewers/{clientId}") {
        if (!runtime.store.forgetViewer(validClientId(call.parameters["clientId"]!!))) throw ApiFailure(404, "VIEWER_NOT_FOUND", "Unknown viewer")
        call.respond(HttpStatusCode.NoContent)
    }
    post("/pairing-codes") {
        val issued = runtime.pairing.issue()
        call.respond(HttpStatusCode.Created, PairingCode(issued.code, issued.expiresAt.toString()))
    }
}

internal fun validClientId(raw: String): String =
    raw.takeIf { CLIENT_ID.matches(it) } ?: throw ApiFailure(400, "INVALID_REQUEST", "clientId must be 1 to 64 letters, digits, dots, dashes or underscores")

private fun label(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL)

private fun ids(list: List<String>, field: String): List<String> {
    if (list.size > MAX_LIST) throw ApiFailure(400, "INVALID_REQUEST", "$field lists more than $MAX_LIST channels")
    if (list.any { it.isEmpty() || it.length > MAX_ID }) throw ApiFailure(400, "INVALID_REQUEST", "$field holds an unusable channel id")
    return list.distinct()
}

/** [body] trimmed to what the server stores: bounded lists of distinct ids, bounded labels; the server stamps the time. */
internal fun validPreferences(body: ViewerPreferences): ViewerPreferences = ViewerPreferences(
    hidden = ids(body.hidden, "hidden"), favorites = ids(body.favorites, "favorites"), recent = ids(body.recent, "recent"),
    name = label(body.name), platform = label(body.platform),
)
