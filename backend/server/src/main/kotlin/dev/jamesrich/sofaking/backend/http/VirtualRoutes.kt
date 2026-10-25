package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.virtual.VirtualChannelService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.Clock
import java.time.Duration

/** The virtual channels: list, create, read, replace and delete them, and read a channel's schedule ahead. */
fun Route.virtualChannelRoutes(channels: VirtualChannelService?, clock: Clock) {
    if (channels == null) return
    get("/virtual-channels") { call.respond(channels.channels()) }
    post("/virtual-channels") {
        call.respond(HttpStatusCode.Created, channels.create(call.body<VirtualChannel>()))
    }
    get("/virtual-channels/{id}") { call.respond(channels.channel(call.parameters["id"]!!)) }
    put("/virtual-channels/{id}") {
        call.respond(channels.update(call.parameters["id"]!!, call.body<VirtualChannel>()))
    }
    delete("/virtual-channels/{id}") {
        channels.delete(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/virtual-channels/{id}/schedule") {
        val hours = (call.request.queryParameters["hours"]?.toLongOrNull() ?: DEFAULT_HOURS).coerceIn(1, MAX_HOURS)
        val now = clock.instant()
        call.respond(channels.schedule(call.parameters["id"]!!, now, now.plus(Duration.ofHours(hours))))
    }
}

private const val DEFAULT_HOURS = 24L
private const val MAX_HOURS = 48L
