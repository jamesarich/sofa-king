package dev.jamesrich.sofaking.backend.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.util.Base64

/** The bearer token a request presents, empty without one. */
internal fun ApplicationCall.bearer(): String =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()

/**
 * The token a request presents: its bearer token, and on a hook the other places Sonarr, Radarr and the tools that
 * call Emby put one: the `X-MediaBrowser-Token` or `X-Emby-Token` header, or a Basic password.
 */
internal fun ApplicationCall.presentedToken(isHook: Boolean): String {
    val bearer = bearer()
    if (bearer.isNotEmpty() || !isHook) return bearer
    val headers = request.headers
    return headers[MEDIA_BROWSER_TOKEN] ?: headers[EMBY_TOKEN] ?: basicPassword().orEmpty()
}

private const val MEDIA_BROWSER_TOKEN = "X-MediaBrowser-Token"
private const val EMBY_TOKEN = "X-Emby-Token"

private fun ApplicationCall.basicPassword(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Basic ") }?.let { header ->
        runCatching { String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim())) }.getOrNull()
    }?.substringAfter(':', "")?.ifEmpty { null }
