package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the desktop, iOS and web players share: the [stream] they hold for the next [prepare], the status and error
 * flows, and the live-resume rule for an engine that cannot seek a live relay, where rejoining the edge after a long
 * pause is reopening the stream. Android's player keeps its item and live window in Media3 instead.
 */
internal abstract class StreamLivePlayer(private val nowMs: () -> Long = ::uptimeMs) : LivePlayer {
    protected var stream: PlaybackStream? = null
        private set
    private val pauseClock = LivePauseClock()

    protected val mutableStatus = MutableStateFlow(PlayerStatus())
    protected val mutableErrors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    override val status: StateFlow<PlayerStatus> = mutableStatus.asStateFlow()
    override val errors: SharedFlow<PlayerError> = mutableErrors.asSharedFlow()

    override val currentChannelId: String? get() = stream?.channelId
    override val currentStreamUrl: String? get() = stream?.url
    override val hasItem: Boolean get() = stream != null

    override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
        this.stream = stream
    }

    override fun replaceStream(stream: PlaybackStream) {
        stop()
        this.stream = stream
    }

    override fun play() {
        val toLive = pauseClock.onPlay(nowMs()) && stream?.seekable != true
        playWhenReady = true
        if (toLive) {
            stop()
            prepare()
        }
    }

    override fun pause() {
        pauseClock.onPause(nowMs())
        playWhenReady = false
    }
}
