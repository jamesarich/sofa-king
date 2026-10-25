package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.contract.PlaybackSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Plays [channelId] for a player that only speaks HTTP. A TS source is fanned out through this response and its
 * session ends with it. An HLS source goes to [hls], which returns true when the player's own media requests now
 * hold the session (a redirect) and false when it was consumed inside this request (a remux). A protocol detected
 * on the first fetch switches paths here instead of reaching the player as 410.
 */
internal suspend fun serveExternal(
    call: ApplicationCall,
    runtime: LiveRuntime,
    channelId: String,
    shareHls: Boolean,
    hls: suspend (PlaybackSession) -> Boolean,
) {
    val sessions = runtime.sessions
    val joined = if (shareHls) sessions.external.joinable(channelId) else null
    if (joined != null) {
        hls(joined)
    } else {
        val opened = sessions.external.open(channelId, call.request.origin.remoteAddress)
        var release = true
        try {
            release = serveOpened(call, runtime, opened, hls)
        } finally {
            if (release) withContext(NonCancellable) { sessions.release(opened.id) }
        }
    }
}

/** Serves [session], once more if the first fetch found the stream changed; true when it is to be released. */
private suspend fun serveOpened(
    call: ApplicationCall,
    runtime: LiveRuntime,
    session: PlaybackSession,
    hls: suspend (PlaybackSession) -> Boolean,
    retried: Boolean = false,
): Boolean {
    if (session.descriptor.protocol == "hls") return !hls(session)
    try {
        runtime.relay.serve(call, session.id, session.descriptor.epoch, session.descriptor.url.substringAfterLast('/'))
    } catch (e: ApiFailure) {
        if (e.code != "STREAM_CHANGED" || retried) throw e
        return serveOpened(call, runtime, runtime.sessions.get(session.id), hls, retried = true)
    }
    return true
}

/**
 * Streams the session's source to [call] as MPEG-TS through FFmpeg; false, since the request consumed the session.
 * FFmpeg reads the source through the loopback relay, not the session, so this request is what holds the session.
 */
internal suspend fun remux(call: ApplicationCall, runtime: LiveRuntime, sessionId: String): Boolean {
    val media = runtime.sessions.media
    media.attach(sessionId, coroutineContext.job)
    val stream = try {
        runtime.feeds.playable(media.offeringOf(sessionId))
    } catch (e: ProviderFailure) {
        throw ApiFailure(
            HttpStatusCode.ServiceUnavailable,
            "SOURCE_UNAVAILABLE",
            "Source could not be resolved",
            true,
            e,
        )
    }
    runtime.feeds.upstream.check(URI(stream.url))
    val bytes = media.bytesOf(sessionId)
    val job = coroutineContext.job
    runtime.remux.stream(call, stream, { bytes.addAndGet(it.toLong()) }) { reason ->
        // A source that stalls or falls below a floor gives way to the channel's next one, in the same response.
        media.failover(sessionId, null, job, reason)?.let { next ->
            runCatchingCancellable {
                runtime.feeds.playable(next).also { runtime.feeds.upstream.check(URI(it.url)) }
            }.getOrNull()
        }
    }
    return false
}
