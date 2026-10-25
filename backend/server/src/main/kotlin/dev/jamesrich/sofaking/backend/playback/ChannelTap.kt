package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Reads a lineup channel for an FFmpeg the server runs itself, such as a virtual channel's live slot or a multiview
 * tile: a playback session of its own picks the channel's healthiest source and holds a session slot while it runs,
 * the way a recording does, and FFmpeg reads that source through [relay].
 */
class ChannelTap(
    private val sessions: SessionManager,
    private val feeds: FeedService,
    private val relay: LoopbackRelay,
) {
    /** Runs [block] on channel [channelId]'s stream, in a session named for [client]. */
    suspend fun <T> reading(channelId: String, client: String, block: suspend (FfmpegSource) -> T): T {
        val session = sessions.external.open(channelId, client, shareable = false)
        try {
            val offering = sessions.media.offeringOf(session.id)
            val stream = feeds.playable(offering)
            feeds.upstream.check(URI(stream.url))
            return relay.reading(stream, offering.protocol, block = block)
        } finally {
            withContext(NonCancellable) { sessions.release(session.id) }
        }
    }
}
