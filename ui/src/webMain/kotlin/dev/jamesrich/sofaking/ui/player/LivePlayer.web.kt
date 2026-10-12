package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.w3c.dom.DISABLED
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.SHOWING
import org.w3c.dom.TextTrackMode
import org.w3c.dom.get
import kotlin.time.Clock

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val player = remember(captionSync) { HtmlLivePlayer(captionSync) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The browser player on one `<video>` element, shared by the preview and the full screen as on every platform.
 * Browsers play HLS natively (Safari, recent Chromium) and have no MPEG-TS demuxer, so a TS-only channel reports
 * an unsupported format.
 */
internal class HtmlLivePlayer(override val captionSync: CaptionSync) : LivePlayer {
    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        setAttribute("playsinline", "")
        style.width = "100%"
        style.height = "100%"
        style.backgroundColor = "black"
        style.setProperty("object-fit", "contain")
    }
    private val pauseClock = LivePauseClock()
    private var stream: PlaybackStream? = null
    private var captionsOn = false

    private val _status = MutableStateFlow(PlayerStatus())
    private val _errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()
    override val errors: SharedFlow<PlayerError> = _errors.asSharedFlow()
    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()

    init {
        video.addEventListener("playing") { _status.update { it.copy(state = PlaybackState.READY, isPlaying = true) } }
        video.addEventListener("pause") { _status.update { it.copy(isPlaying = false) } }
        video.addEventListener("waiting") { _status.update { it.copy(state = PlaybackState.BUFFERING) } }
        video.addEventListener("canplay") { _status.update { it.copy(state = PlaybackState.READY) } }
        video.addEventListener("ended") { _status.update { it.copy(state = PlaybackState.ENDED, isPlaying = false) } }
        video.addEventListener("loadedmetadata") { applyCaptions() }
        video.addEventListener("error") {
            _status.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
            // MEDIA_ERR_SRC_NOT_SUPPORTED (4) is a format the browser cannot play; anything else is the network.
            val code = if (video.error?.code?.toInt() == 4) "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" else "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
            _errors.tryEmit(PlayerError(code))
        }
    }

    override val currentChannelId: String? get() = stream?.channelId
    override val currentStreamUrl: String? get() = stream?.url
    override val hasItem: Boolean get() = stream != null
    override val bufferedMs: Long get() = 0L
    override val hasTextTrack: Boolean get() = video.textTracks.length > 0

    override var volume: Float
        get() = video.volume.toFloat()
        set(value) {
            video.volume = value.toDouble()
            video.muted = value == 0f
        }

    override var playWhenReady: Boolean
        get() = _status.value.playWhenReady
        set(value) {
            _status.update { it.copy(playWhenReady = value) }
            if (value) startPlayback() else video.pause()
        }

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        applyCaptions()
    }

    override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
        this.stream = stream
    }

    override fun replaceStream(stream: PlaybackStream) {
        stop()
        this.stream = stream
    }

    override fun prepare() {
        val url = stream?.url ?: return
        _status.update { it.copy(state = PlaybackState.BUFFERING) }
        video.src = url
        if (playWhenReady) startPlayback()
    }

    override fun stop() {
        video.pause()
        video.removeAttribute("src")
        video.load()
        _status.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
    }

    override fun play() {
        val toLive = pauseClock.onPlay(Clock.System.now().toEpochMilliseconds())
        playWhenReady = true
        if (toLive) {
            stop()
            prepare()
        }
    }

    override fun pause() {
        pauseClock.onPause(Clock.System.now().toEpochMilliseconds())
        playWhenReady = false
    }

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = object : MediaSessionHandle {
        override fun publish(nowPlaying: NowPlaying) {
            document.title = "${nowPlaying.title} - Sofa King"
        }

        override fun close() {
            document.title = "Sofa King"
        }
    }

    @Composable
    override fun Video(modifier: Modifier, shutter: @Composable () -> Unit) {
        val state by status.collectAsState()
        Box(modifier) {
            WebElementView(factory = { video }, modifier = Modifier.matchParentSize())
            if (state.state != PlaybackState.READY) shutter()
        }
    }

    override fun release() {
        stop()
    }

    /** Autoplay with sound needs a user gesture first; a rejected play() leaves the item paused, as a remote's pause would. */
    private fun startPlayback() {
        video.play()
    }

    private fun applyCaptions() {
        val tracks = video.textTracks
        for (i in 0 until tracks.length) {
            tracks[i]?.mode = if (captionsOn && i == 0) TextTrackMode.SHOWING else TextTrackMode.DISABLED
        }
    }
}
