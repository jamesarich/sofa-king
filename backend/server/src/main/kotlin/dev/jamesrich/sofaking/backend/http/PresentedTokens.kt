package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.playback.PlaybackClient
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.util.AttributeKey
import java.util.Base64

/** The paired device whose token the request presented, which the token guard sets. */
internal val PresentedClientKey = AttributeKey<String>("PresentedClient")

/** Who is asking for playback: the player's address, and the paired device whose token it presented. */
internal fun ApplicationCall.playbackClient(): PlaybackClient =
    PlaybackClient(request.origin.remoteAddress, attributes.getOrNull(PresentedClientKey))

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
