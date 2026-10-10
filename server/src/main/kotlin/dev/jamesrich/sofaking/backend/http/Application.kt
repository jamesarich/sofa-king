package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.IdentityStore
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private val wireJson = Json { encodeDefaults = true }
fun Application.backendModule(store: IdentityStore, clock: Clock, runtime: LiveRuntime? = null, apiToken: String? = null) {
    install(ContentNegotiation) { json(wireJson) }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ApiError("NOT_FOUND", "Resource not found", false)) }
        exception<Throwable> { call, cause ->
            when (cause) {
                is CancellationException -> throw cause
                is ApiFailure -> call.respond(HttpStatusCode.fromValue(cause.status), ApiError(cause.code, cause.message, cause.retryable))
                is BadRequestException, is kotlinx.serialization.SerializationException -> call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REQUEST", "Invalid request body", false))
                else -> {
                    // Exception messages can contain upstream signed URLs; keep them out of logs.
                    call.application.log.error("Request failed ({})", cause.javaClass.simpleName)
                    call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Request failed", true))
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Call) {
        if (call.request.path().startsWith("/api/") && apiToken != null) {
            val presented = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
            if (!MessageDigest.isEqual(apiToken.toByteArray(), presented.toByteArray())) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("AUTH_REQUIRED", "Bearer token required", false))
                finish()
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
            else call.respond(Bootstrap(serverId = store.serverId(), serverTime = clock.instant().toString(), capabilities = Capabilities(catalog = runtime != null, guide = runtime != null, playback = runtime != null)))
        }
        if (runtime != null) {
            get("/api/v1/channels") {
                val catalog = runtime.feeds.catalog()
                val etag = "\"${catalog.revision}\""
                call.response.header(HttpHeaders.ETag, etag)
                if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) call.respond(HttpStatusCode.NotModified)
                else call.respond(catalog)
            }
            get("/api/v1/providers") { call.respond(runtime.feeds.providers()) }
            get("/api/v1/sources") { call.respond(runtime.feeds.sources()) }
            patch("/api/v1/sources/{id}") {
                val source = runtime.feeds.updateSource(call.parameters["id"]!!, call.body<UpdateSource>())
                runtime.sessions.healthChanged(source.id)
                call.respond(source)
            }
            post("/api/v1/guide/query") { call.respond(runtime.feeds.guide(call.body<GuideQuery>())) }
            post("/api/v1/playback-sessions") { call.respond(HttpStatusCode.Created, runtime.sessions.open(call.body<OpenPlayback>())) }
            get("/api/v1/playback-sessions/{id}") { call.respond(runtime.sessions.get(call.parameters["id"]!!)) }
            patch("/api/v1/playback-sessions/{id}") { call.respond(runtime.sessions.update(call.parameters["id"]!!, call.body<UpdatePlayback>())) }
            delete("/api/v1/playback-sessions/{id}") { runtime.sessions.release(call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
            get("/media/sessions/{id}/{epoch}/{token}") {
                val epoch = call.parameters["epoch"]?.toIntOrNull() ?: throw ApiFailure(400, "INVALID_REQUEST", "Invalid stream epoch")
                runtime.relay.serve(call, call.parameters["id"]!!, epoch, call.parameters["token"]!!)
            }
        }
    }
}
private suspend inline fun <reified T> ApplicationCall.body(): T {
    val bytes = receiveChannel().readBuffer(65_537L).readByteArray()
    if (bytes.size > 65_536) throw ApiFailure(413, "REQUEST_TOO_LARGE", "Request exceeds 64 KiB")
    return wireJson.decodeFromString(bytes.toString(Charsets.UTF_8))
}
