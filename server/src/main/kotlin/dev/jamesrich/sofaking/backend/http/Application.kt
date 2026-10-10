package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.ApiError
import dev.jamesrich.sofaking.backend.api.Bootstrap
import dev.jamesrich.sofaking.backend.store.IdentityStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

fun Application.backendModule(store: IdentityStore, clock: Clock) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("NOT_FOUND", "Resource not found", false))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Request failed", true))
        }
    }
    routing {
        get("/healthz") { call.respondText("ok") }
        get("/readyz") {
            if (store.ready()) call.respondText("ready")
            else call.respond(HttpStatusCode.ServiceUnavailable, ApiError("NOT_READY", "Storage unavailable", true))
        }
        get("/api/v1/bootstrap") {
            if (!store.ready()) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("NOT_READY", "Storage unavailable", true))
            } else {
                call.respond(Bootstrap(serverId = store.serverId(), serverTime = clock.instant().toString()))
            }
        }
    }
}
