package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private val requestJson = Json { encodeDefaults = true }

internal const val CONTROL_BODY_LIMIT = 64 * 1024L

/** The 413 message for a body over [limit] bytes. */
internal fun tooLargeMessage(limit: Long): String =
    "Request exceeds " + if (limit % (1024 * 1024) == 0L) "${limit / (1024 * 1024)} MiB" else "${limit / 1024} KiB"

/** Control requests are small; the policy write has its own 4 MiB reader. */
internal suspend inline fun <reified T> ApplicationCall.body(): T {
    val bytes = receiveChannel().readBuffer(CONTROL_BODY_LIMIT + 1).readByteArray()
    if (bytes.size > CONTROL_BODY_LIMIT) throw ApiFailure(413, "REQUEST_TOO_LARGE", tooLargeMessage(CONTROL_BODY_LIMIT))
    return requestJson.decodeFromString(bytes.toString(Charsets.UTF_8))
}
