package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.PlaybackStream

/** Multiview, on a server that draws it, through the same one session the channels play through. */
interface TvMultiview {
    /**
     * Opens a multiview of [channelIds], two to four, with the sound of tile [audio], in place of the held session:
     * one stream the server draws them into, left to right and top to bottom.
     */
    @Throws(Exception::class)
    suspend fun multiview(channelIds: List<String>, audio: Int): PlaybackStream

    /** Moves the held multiview's sound to [tile]; the stream it restarts as, or null when no multiview is held. */
    @Throws(Exception::class)
    suspend fun moveMultiviewAudio(tile: Int): PlaybackStream?
}

internal class TvMultiviewCalls(private val parts: TvParts) : TvMultiview {
    private val viewModel = parts.viewModel

    @Throws(Exception::class)
    override suspend fun multiview(channelIds: List<String>, audio: Int): PlaybackStream = parts.opening {
        viewModel.sessions.multiview(channelIds, audio)
    }

    @Throws(Exception::class)
    override suspend fun moveMultiviewAudio(tile: Int): PlaybackStream? = viewModel.sessions.moveAudio(tile)
}
