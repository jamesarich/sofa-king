package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.SaveNotificationTarget
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.notify.NotificationTargetsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/**
 * The notification targets under `/api/v1/admin/notifications`: list and add them, read, change and remove one, send
 * one a test, and read its recent deliveries.
 */
internal fun Route.notificationRoutes(notifications: NotificationTargetsService?) {
    fun loaded(): NotificationTargetsService = notifications
        ?: throw ApiFailure(
            HttpStatusCode.NotFound,
            "NOTIFICATIONS_NOT_AVAILABLE",
            "Notifications are not available on this server",
        )
    get("/notifications") { call.respond(loaded().list()) }
    post("/notifications") {
        call.respond(HttpStatusCode.Created, loaded().create(call.body<SaveNotificationTarget>()))
    }
    get("/notifications/{id}") { call.respond(loaded().get(call.parameters["id"]!!)) }
    patch("/notifications/{id}") {
        call.respond(loaded().update(call.parameters["id"]!!, call.body<SaveNotificationTarget>()))
    }
    delete("/notifications/{id}") {
        loaded().delete(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/notifications/{id}/test") { call.respond(loaded().test(call.parameters["id"]!!)) }
    get("/notifications/{id}/deliveries") { call.respond(loaded().deliveries(call.parameters["id"]!!)) }
}
