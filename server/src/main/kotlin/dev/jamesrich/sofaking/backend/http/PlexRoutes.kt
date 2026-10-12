package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.plex.PlexSync
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Plex sync under the admin route: the last sync's status, and a sync now. */
fun Route.plexRoutes(plex: PlexSync?) {
    fun loaded(): PlexSync = plex ?: throw ApiFailure(404, "PLEX_NOT_CONFIGURED", "Plex sync is not configured; set SOFA_PLEX_URL and SOFA_PLEX_TOKEN")
    get("/plex") { call.respond(loaded().status()) }
    post("/plex/sync") { call.respond(loaded().sync("api")) }
}
