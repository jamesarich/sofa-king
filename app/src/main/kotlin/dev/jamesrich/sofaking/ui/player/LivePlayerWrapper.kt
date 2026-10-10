package dev.jamesrich.sofaking.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player the MediaSession sees. System next and previous (Assistant, media keys) zap channels through
 * [onZapStep]; play, pause and the resume-to-live rule apply to every path, not just the remote's keys.
 */
@OptIn(UnstableApi::class)
internal class LivePlayerWrapper(private val live: ExoPlayer) : ForwardingPlayer(live) {
    /** +1 for the next channel, -1 for the previous one. */
    var onZapStep: (Int) -> Unit = {}

    private val metadataListeners = CopyOnWriteArraySet<Player.Listener>()
    private var nowPlaying: MediaMetadata = MediaMetadata.EMPTY
    private var pausedAtMs: Long? = null

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

    override fun play() {
        if (shouldResumeAtLive(pausedAtMs, SystemClock.elapsedRealtime())) live.seekToDefaultPosition()
        pausedAtMs = null
        super.play()
    }

    override fun pause() {
        if (pausedAtMs == null) pausedAtMs = SystemClock.elapsedRealtime()
        super.pause()
    }

    override fun getMediaMetadata(): MediaMetadata = nowPlaying

    /**
     * Swaps the now-playing metadata and tells the session, without touching the stream.
     * Re-preparing is avoided because only the metadata changes.
     */
    fun publishNowPlaying(metadata: MediaMetadata) {
        if (metadata == nowPlaying) return
        nowPlaying = metadata
        metadataListeners.forEach { it.onMediaMetadataChanged(metadata) }
    }
}
