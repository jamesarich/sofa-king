package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private val requestJson = Json { encodeDefaults = true }

/** The app's requests drop fields this server does not know, so a newer app keeps working; admin writes stay strict. */
private val appRequestJson = Json(requestJson) { ignoreUnknownKeys = true }

internal const val CONTROL_BODY_LIMIT = 64 * 1024L

/** The 413 message for a body over [limit] bytes. */
internal fun tooLargeMessage(limit: Long): String =
    "Request exceeds " + if (limit % (1024 * 1024) == 0L) "${limit / (1024 * 1024)} MiB" else "${limit / 1024} KiB"

/** Control requests are small; the policy write has its own 4 MiB reader. */
internal suspend inline fun <reified T> ApplicationCall.body(): T = requestJson.decodeFromString(controlBody())

/** A request body from the app: guide queries and playback sessions. */
internal suspend inline fun <reified T> ApplicationCall.appBody(): T = appRequestJson.decodeFromString(controlBody())

internal suspend fun ApplicationCall.controlBody(): String {
    val bytes = receiveChannel().readBuffer(CONTROL_BODY_LIMIT + 1).readByteArray()
    if (bytes.size > CONTROL_BODY_LIMIT) throw ApiFailure(413, "REQUEST_TOO_LARGE", tooLargeMessage(CONTROL_BODY_LIMIT))
    return bytes.toString(Charsets.UTF_8)
}
