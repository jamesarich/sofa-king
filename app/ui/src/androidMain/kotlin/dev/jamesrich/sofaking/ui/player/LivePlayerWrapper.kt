package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The player the MediaSession sees. System next and previous (Assistant, media keys) zap channels through
 * [onZapStep]; play and pause go through [live], so the resume-to-live rule applies to every path. It listens to
 * the player from its creation until the player is released, so one wrapper serves each session in turn.
 */
@OptIn(UnstableApi::class)
internal class LivePlayerWrapper(private val live: ExoLivePlayer) : ForwardingSimpleBasePlayer(live.exo) {
    /** The open session's zap; nothing while none is open. */
    var onZapStep: (Int) -> Unit = {}

    private var nowPlaying: MediaMetadata = MediaMetadata.EMPTY

    /** The player's own state, with next and previous offered whatever the stream allows and the session's metadata. */
    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon()
            .setAvailableCommands(state.availableCommands.buildUpon().addAll(*ZAP_COMMANDS).build())
            .setPlaylist(state.timeline, state.currentTracks, nowPlaying)
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onZapStep(1)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onZapStep(-1)
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) live.play() else live.pause()
        return Futures.immediateVoidFuture()
    }

    /** Swaps the now-playing metadata and tells the session, without touching the stream. */
    fun publishNowPlaying(metadata: MediaMetadata) {
        if (metadata == nowPlaying) return
        nowPlaying = metadata
        invalidateState()
    }
}

private val ZAP_COMMANDS = intArrayOf(
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
)
