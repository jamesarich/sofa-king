package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.SavedSecret
import dev.jamesrich.sofaking.backend.api.SaveFanartKey
import dev.jamesrich.sofaking.backend.api.SaveListenBrainzToken
import dev.jamesrich.sofaking.backend.api.SaveTmdbToken
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Setup under the admin route: the source file as written and its replacement, the starter policy, and the
 * TMDB token, fanart.tv key and ListenBrainz token. A write restarts the server once the response is sent, since
 * providers and the policy are built from the files at start.
 */
fun Route.setupRoutes(setup: SetupService?) {
    fun service(): SetupService = setup
        ?: throw ApiFailure(
            HttpStatusCode.ServiceUnavailable,
            "SETUP_UNAVAILABLE",
            "Setup is not available on this server",
        )
    get("/source-file") { call.respond(service().document()) }
    put("/source-file") {
        val starter =
            call.request.queryParameters["starterPolicy"]?.let {
                it == "true" ||
                    throw ApiFailure(
                        HttpStatusCode.BadRequest,
                        "INVALID_REQUEST",
                        "starterPolicy must be true when given",
                    )
            }
                ?: false
        call.respond(service().replaceSources(call.body<SourceDocument>(), starterPolicy = starter))
    }
    post("/policy/starter") { call.respond(HttpStatusCode.Created, service().writeStarterPolicy()) }
    put("/tmdb-token") { call.respond(service().save(SavedSecret.TMDB, call.body<SaveTmdbToken>().token)) }
    put("/fanart-key") { call.respond(service().save(SavedSecret.FANART, call.body<SaveFanartKey>().key)) }
    put("/listenbrainz-token") {
        call.respond(service().save(SavedSecret.LISTENBRAINZ, call.body<SaveListenBrainzToken>().token))
    }
}
