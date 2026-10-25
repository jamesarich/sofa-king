package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.NamedOutput
import dev.jamesrich.sofaking.backend.api.UpdateHdhrAccess
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.plex.PlexSync
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Plex sync under the admin route: the last sync's status, and a sync now. */
fun Route.plexRoutes(plex: PlexSync?) {
    fun loaded(): PlexSync = plex
        ?: throw ApiFailure(
            HttpStatusCode.NotFound,
            "PLEX_NOT_CONFIGURED",
            "Plex sync is not configured; set SOFA_PLEX_URL and SOFA_PLEX_TOKEN",
        )
    get("/plex") { call.respond(loaded().status()) }
    post("/plex/sync") { call.respond(loaded().sync("api")) }
}

/**
 * Who may reach the HDHomeRun emulation under the admin route: the lists and the refused clients,
 * and the portal's list replaced.
 */
fun Route.hdhrAccessRoutes(access: HdhrAccess, enabled: Boolean) {
    get("/hdhr/access") { call.respond(access.status(enabled)) }
    put("/hdhr/access") {
        val request = call.body<UpdateHdhrAccess>()
        try {
            access.replace(request.allowed)
        } catch (failure: IllegalArgumentException) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                failure.message ?: "Not an IP address or CIDR block",
                cause = failure,
            )
        }
        call.respond(access.status(enabled))
    }
}

/** The named player outputs: list them, save one by name, delete one. */
fun Route.namedOutputRoutes(outputs: NamedOutputs) {
    get("/outputs") { call.respond(outputs.list()) }
    put("/outputs/{name}") {
        val request = call.body<NamedOutput>()
        call.respond(outputs.put(request.copy(name = call.parameters["name"]!!)))
    }
    delete("/outputs/{name}") {
        if (!outputs.delete(call.parameters["name"]!!)) {
            throw ApiFailure(HttpStatusCode.NotFound, "OUTPUT_NOT_FOUND", "Unknown output")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
