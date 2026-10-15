package dev.jamesrich.sofaking.backend.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

private val requestJson = Json { encodeDefaults = true }

/** The app's requests drop fields this server does not know, so a newer app keeps working; admin writes stay strict. */
private val appRequestJson = Json(requestJson) { ignoreUnknownKeys = true }

internal const val CONTROL_BODY_LIMIT = 64 * 1024L

/** The 413 message for a body over [limit] bytes. */
internal fun tooLargeMessage(limit: Long): String =
    "Request exceeds " + if (limit % (1024 * 1024) == 0L) "${limit / (1024 * 1024)} MiB" else "${limit / 1024} KiB"

/**
 * Control requests are small; the policy write may carry 4 MiB. The body is decoded as it streams in, with no copy
 * through a String; `RequestBodyLimit` bounds the read, so an oversized body fails as 413 part way.
 */
@OptIn(ExperimentalSerializationApi::class)
internal suspend inline fun <reified T> ApplicationCall.body(): T = requestJson.decodeFromStream(receiveStream())

/** A request body from the app: guide queries and playback sessions. */
@OptIn(ExperimentalSerializationApi::class)
internal suspend inline fun <reified T> ApplicationCall.appBody(): T = appRequestJson.decodeFromStream(receiveStream())
