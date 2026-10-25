package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.multiview.MultiviewSessions
import dev.jamesrich.sofaking.contract.OpenMultiview
import dev.jamesrich.sofaking.contract.UpdateMultiview
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/**
 * Multiview: `/api/v1/multiview-sessions` opens, renews, changes the sound of and releases a session, and its stream
 * is under `/media/multiview/{id}/{epoch}/{secret}/{name}`, which takes no token: the secret in the path is the
 * session's own.
 */
fun Route.multiviewRoutes(multiview: MultiviewSessions) {
    post("/api/v1/multiview-sessions") {
        call.respond(HttpStatusCode.Created, multiview.open(call.appBody<OpenMultiview>()))
    }
    get("/api/v1/multiview-sessions/{id}") { call.respond(multiview.get(call.parameters["id"]!!)) }
    patch("/api/v1/multiview-sessions/{id}") {
        call.respond(multiview.update(call.parameters["id"]!!, call.appBody<UpdateMultiview>()))
    }
    delete("/api/v1/multiview-sessions/{id}") {
        multiview.release(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/media/multiview/{id}/{epoch}/{secret}/{name}") {
        val params = call.parameters
        val (file, type) = params["epoch"]?.toIntOrNull()
            ?.let { epoch -> multiview.media(params["id"]!!, epoch, params["secret"]!!, params["name"]!!) }
            ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown media")
        call.response.header(
            HttpHeaders.CacheControl,
            if (type.endsWith("mpegurl")) "no-store" else "private, max-age=60",
        )
        call.respond(LocalPathContent(file, ContentType.parse(type)))
    }
}
