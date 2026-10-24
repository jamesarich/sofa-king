package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A player for a host that cannot play (no libVLC installed): every prepare fails with [code], so the guide and
 * the full screen show their failure cards instead of a black box, and the rest of the app works.
 */
internal class UnavailableLivePlayer(override val captionSync: CaptionSync, private val code: String) :
    LivePlayer,
    PlayerTracks by NoTracks {
    private val _errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    private var stream: PlaybackStream? = null

    override val status: StateFlow<PlayerStatus> = MutableStateFlow(PlayerStatus()).asStateFlow()
    override val errors: SharedFlow<PlayerError> = _errors.asSharedFlow()
    override val currentChannelId: String? get() = stream?.channelId
    override val currentStreamUrl: String? get() = stream?.url
    override val hasItem: Boolean get() = stream != null
    override val bufferedMs: Long = 0L
    override val supportsCaptionLead: Boolean = false
    override var volume: Float = 1f
    override var playWhenReady: Boolean = true

    override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
        this.stream = stream
    }

    override fun replaceStream(stream: PlaybackStream) {
        this.stream = stream
    }

    override fun prepare() {
        _errors.tryEmit(PlayerError(code))
    }

    override fun stop() = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = NoMediaSession

    @Composable
    override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) {
        Box(modifier) { shutter() }
    }

    override fun release() = Unit
}
