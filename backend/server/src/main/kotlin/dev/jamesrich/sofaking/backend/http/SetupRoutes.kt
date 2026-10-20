package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/**
 * Setup under the admin route: the source file as written and its replacement, and the starter policy. A write
 * restarts the server once the response is sent, since providers and the policy are built from the files at start.
 */
fun Route.setupRoutes(setup: SetupService?) {
    fun service(): SetupService = setup ?: throw ApiFailure(503, "SETUP_UNAVAILABLE", "Setup is not available on this server")
    get("/source-file") { call.respond(service().document()) }
    put("/source-file") {
        val starter = call.request.queryParameters["starterPolicy"]?.let { it == "true" || throw ApiFailure(400, "INVALID_REQUEST", "starterPolicy must be true when given") } ?: false
        call.respond(service().replaceSources(call.body<SourceDocument>(), starterPolicy = starter))
    }
    post("/policy/starter") { call.respond(HttpStatusCode.Created, service().writeStarterPolicy()) }
}
