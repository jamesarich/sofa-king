package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player the MediaSession sees. System next and previous (Assistant, media keys) zap channels through
 * [onZapStep]; play and pause go through [live], so the resume-to-live rule applies to every path.
 */
@OptIn(UnstableApi::class)
internal class LivePlayerWrapper(
    private val live: ExoLivePlayer,
    private val onZapStep: (Int) -> Unit,
) : ForwardingPlayer(live.exo) {
    private val metadataListeners = CopyOnWriteArraySet<Player.Listener>()
    private var nowPlaying: MediaMetadata = MediaMetadata.EMPTY

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        metadataListeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        metadataListeners.remove(listener)
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()

    override fun seekToNext() = onZapStep(1)

    override fun seekToNextMediaItem() = onZapStep(1)

    override fun seekToPrevious() = onZapStep(-1)

    override fun seekToPreviousMediaItem() = onZapStep(-1)

    override fun play() = live.play()

    override fun pause() = live.pause()

    override fun getMediaMetadata(): MediaMetadata = nowPlaying

    /** Swaps the now-playing metadata and tells the session, without touching the stream. */
    fun publishNowPlaying(metadata: MediaMetadata) {
        if (metadata == nowPlaying) return
        nowPlaying = metadata
        metadataListeners.forEach { it.onMediaMetadataChanged(metadata) }
    }
}
