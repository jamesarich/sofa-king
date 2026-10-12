package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private val requestJson = Json { encodeDefaults = true }

/** Control requests are small; the policy write has its own 4 MiB reader. */
internal suspend inline fun <reified T> ApplicationCall.body(): T {
    val bytes = receiveChannel().readBuffer(65_537L).readByteArray()
    if (bytes.size > 65_536) throw ApiFailure(413, "REQUEST_TOO_LARGE", "Request exceeds 64 KiB")
    return requestJson.decodeFromString(bytes.toString(Charsets.UTF_8))
}
