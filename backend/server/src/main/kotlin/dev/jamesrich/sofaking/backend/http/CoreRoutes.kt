package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.UpdateSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.store.IdentityStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Bootstrap
import dev.jamesrich.sofaking.contract.Capabilities
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.UpdatePlayback
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import java.time.Clock

/** The health checks, and the bootstrap that tells an app what this server offers. */
internal fun Route.healthRoutes(store: IdentityStore, clock: Clock, runtime: LiveRuntime?) {
    get("/healthz") { call.respondText("ok") }
    get("/readyz") {
        if (store.ready()) {
            call.respondText("ready")
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("NOT_READY", "Storage unavailable", true))
        }
    }
    get("/api/v1/bootstrap") {
        if (!store.ready()) {
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("NOT_READY", "Storage unavailable", true))
        } else {
            call.respond(
                Bootstrap(
                    serverId = store.serverId(),
                    serverTime = clock.instant().toString(),
                    capabilities = Capabilities(
                        catalog = runtime != null,
                        guide = runtime != null,
                        playback = runtime != null,
                        viewers = runtime != null,
                        library = runtime?.libraries != null,
                        recordings = runtime?.dvr != null,
                    ),
                ),
            )
        }
    }
}

/** The channels, providers, sources and guide. */
internal fun Route.liveRoutes(runtime: LiveRuntime) {
    get("/api/v1/channels") {
        // The revision is cheap; the body is not, so answer a matching If-None-Match before building it.
        // Logos are server-relative, so a client behind any proxy resolves them on its own base URL.
        val revision = runtime.catalog.catalogRevision()
        val etag = "\"${hash(revision)}\""
        call.response.header(HttpHeaders.ETag, etag)
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            val catalog = runtime.catalog.catalog(revision)
            call.respond(
                catalog.copy(
                    channels = catalog.channels.map { channel ->
                        channel.copy(logo = channel.logo?.let { runtime.logos.publicUrl("", it) })
                    },
                ),
            )
        }
    }
    get("/api/v1/providers") { call.respond(runtime.feeds.statuses) }
    get("/api/v1/sources") { call.respond(runtime.feeds.sources()) }
    patch("/api/v1/sources/{id}") {
        val source = runtime.feeds.updateSource(call.parameters["id"]!!, call.body<UpdateSource>())
        runtime.sessions.healthChanged(source.id)
        call.respond(source)
    }
    post("/api/v1/guide/query") { call.respond(runtime.catalog.guide(call.appBody<GuideQuery>())) }
}

/** The live playback sessions and the media they serve. */
internal fun Route.playbackRoutes(runtime: LiveRuntime) {
    post("/api/v1/playback-sessions") {
        call.respond(
            HttpStatusCode.Created,
            runtime.sessions.open(call.appBody<OpenPlayback>(), call.request.origin.remoteAddress),
        )
    }
    get("/api/v1/playback-sessions/{id}") { call.respond(runtime.sessions.get(call.parameters["id"]!!)) }
    patch("/api/v1/playback-sessions/{id}") {
        call.respond(runtime.sessions.update(call.parameters["id"]!!, call.appBody<UpdatePlayback>()))
    }
    get("/api/v1/playback-sessions/{id}/ad-break") {
        val ahead = call.request.queryParameters["aheadMs"]?.let {
            it.toLongOrNull() ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid aheadMs")
        } ?: 0L
        call.respond(runtime.sessions.adBreak(call.parameters["id"]!!, ahead))
    }
    delete("/api/v1/playback-sessions/{id}") {
        runtime.sessions.release(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/media/sessions/{id}/{epoch}/{token}") {
        val epoch = call.parameters["epoch"]?.toIntOrNull()
            ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid stream epoch")
        runtime.relay.serve(call, call.parameters["id"]!!, epoch, call.parameters["token"]!!)
    }
}
