package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Reads a lineup channel for an FFmpeg the server runs itself, such as a virtual channel's live slot or a multiview
 * tile: a playback session of its own picks the channel's healthiest source and holds a session slot while it runs,
 * the way a recording does, and FFmpeg reads that source through [relay]. The read is attached to the session, so
 * the idle lease never ends it, and ending the session ends the read.
 */
class ChannelTap(
    private val sessions: SessionManager,
    private val feeds: FeedService,
    private val relay: LoopbackRelay,
) {
    /**
     * Runs [block] on channel [channelId]'s stream, in a session named for [client]. A session that ends under the
     * read (an operator stopped it) fails the read with `SESSION_EXPIRED`, so the caller opens a new one; only the
     * caller's own cancellation cancels it.
     */
    suspend fun <T> reading(channelId: String, client: String, block: suspend (FfmpegSource) -> T): T {
        val session = sessions.external.open(channelId, client, shareable = false, hold = true)
        try {
            return try {
                coroutineScope {
                    sessions.media.attach(session.id, coroutineContext.job)
                    val offering = sessions.media.offeringOf(session.id)
                    val stream = feeds.playable(offering)
                    feeds.upstream.check(URI(stream.url))
                    relay.reading(stream, offering.protocol, block = block)
                }
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_EXPIRED, "The tap's session ended")
            }
        } finally {
            withContext(NonCancellable) { sessions.release(session.id) }
        }
    }
}
