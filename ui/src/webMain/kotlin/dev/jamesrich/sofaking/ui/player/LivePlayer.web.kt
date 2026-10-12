package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.webgl.WebGLRenderTarget
import androidx.compose.ui.platform.webgl.rememberWebGLRenderTarget
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.HtmlElementView
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.HLS_MIME_TYPE
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.khronos.webgl.WebGLRenderingContext
import org.w3c.dom.DISABLED
import org.w3c.dom.HIDDEN
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.SHOWING
import org.w3c.dom.TextTrack
import org.w3c.dom.TextTrackCue
import org.w3c.dom.TextTrackMode
import org.w3c.dom.get
import org.w3c.dom.url.URL
import kotlin.time.Clock

private val log = Logger.withTag("SofaKing")

private val HAVE_CURRENT_DATA = HTMLMediaElement.HAVE_CURRENT_DATA

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
        // A cross-origin stream's frames can be drawn into WebGL only when fetched with CORS.
        crossOrigin = "anonymous"
        style.width = "100%"
        style.height = "100%"
        style.backgroundColor = "black"
        style.setProperty("object-fit", "contain")
    }
    private val pauseClock = LivePauseClock()
    private var stream: PlaybackStream? = null
    private var captionsOn = false
    private var cueTrack: TextTrack? = null
    private var loaded = false

    private val drawsInCanvas = MutableStateFlow(true)
    private val frameSize = MutableStateFlow(IntSize(1, 1))

    private val _status = MutableStateFlow(PlayerStatus())
    private val _errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    private val _cues = MutableStateFlow<List<CaptionCue>>(emptyList())
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()
    override val errors: SharedFlow<PlayerError> = _errors.asSharedFlow()
    override val cues: StateFlow<List<CaptionCue>> = _cues.asStateFlow()

    init {
        video.addEventListener("playing") { _status.update { it.copy(state = PlaybackState.READY, isPlaying = true) } }
        video.addEventListener("pause") { _status.update { it.copy(isPlaying = false) } }
        video.addEventListener("waiting") { _status.update { it.copy(state = PlaybackState.BUFFERING) } }
        video.addEventListener("canplay") { _status.update { it.copy(state = PlaybackState.READY) } }
        video.addEventListener("ended") { _status.update { it.copy(state = PlaybackState.ENDED, isPlaying = false) } }
        video.addEventListener("loadedmetadata") {
            loaded = true
            updateFrameSize()
            applyCaptions()
        }
        video.addEventListener("resize") { updateFrameSize() }
        video.textTracks.addEventListener("addtrack") { applyCaptions() }
        video.addEventListener("error") {
            // A cross-origin HLS stream that fails before loading may come from a server that sends no CORS headers
            // on media: play it again as a plain element, which needs none. TS fails for its format and reports.
            val current = stream
            if (drawsInCanvas.value && !loaded && current?.mimeType == HLS_MIME_TYPE && isCrossOrigin(current.url)) {
                video.crossOrigin = null
                useElement()
                prepare()
                return@addEventListener
            }
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
        loaded = false
        video.src = url
        if (playWhenReady) startPlayback()
    }

    override fun stop() {
        video.pause()
        video.removeAttribute("src")
        video.load()
        cueTrack = null
        _cues.value = emptyList()
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

    /**
     * Draws each decoded frame into a WebGL texture inside Compose's canvas, so overlays draw over the picture as on
     * other platforms. An element view would sit above the canvas and hide them; it remains the fallback when the
     * browser has no WebGL2 or the stream is cross-origin without CORS, which taints the frames.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Video(modifier: Modifier, shutter: @Composable () -> Unit) {
        val state by status.collectAsState()
        val inCanvas by drawsInCanvas.collectAsState()
        val size by frameSize.collectAsState()
        val target = if (inCanvas) rememberWebGLRenderTarget(size) else null
        LaunchedEffect(target) {
            if (target == null) return@LaunchedEffect
            var shown = -1.0
            while (true) {
                withFrameNanos { }
                val time = video.currentTime
                if (time == shown && target.size == size) continue
                if (video.readyState < HAVE_CURRENT_DATA || video.videoWidth != size.width || video.videoHeight != size.height) continue
                try {
                    if (target.render { upload(target) }) shown = time
                } catch (e: Throwable) {
                    log.w(e) { "drawing video frames in the canvas failed; using the element" }
                    useElement()
                    break
                }
            }
        }
        Box(modifier.background(Color.Black)) {
            if (target != null) {
                Image(target.painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
            } else {
                HtmlElementView(factory = { video }, modifier = Modifier.matchParentSize())
            }
            if (state.state != PlaybackState.READY) shutter()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun upload(target: WebGLRenderTarget) {
        val gl = target.webGLContext
        if (target.size.width != video.videoWidth || target.size.height != video.videoHeight) return
        gl.bindTexture(WebGLRenderingContext.TEXTURE_2D, target.webGlTexture)
        // The adopted texture's origin is bottom-left; the video's first row is its top.
        gl.pixelStorei(WebGLRenderingContext.UNPACK_FLIP_Y_WEBGL, 1)
        try {
            gl.texSubImage2D(WebGLRenderingContext.TEXTURE_2D, 0, 0, 0, WebGLRenderingContext.RGBA, WebGLRenderingContext.UNSIGNED_BYTE, video)
        } finally {
            gl.pixelStorei(WebGLRenderingContext.UNPACK_FLIP_Y_WEBGL, 0)
            gl.bindTexture(WebGLRenderingContext.TEXTURE_2D, null)
        }
    }

    private fun useElement() {
        drawsInCanvas.value = false
        applyCaptions()
    }

    override fun release() {
        stop()
    }

    /** Autoplay with sound needs a user gesture first; a rejected play() leaves the item paused, as a remote's pause would. */
    @OptIn(ExperimentalWasmJsInterop::class)
    private fun startPlayback() {
        video.play()
    }

    private fun updateFrameSize() {
        if (video.videoWidth > 0 && video.videoHeight > 0) frameSize.value = IntSize(video.videoWidth, video.videoHeight)
    }

    /**
     * The first text track is on while captions are. Drawn in the canvas, it stays HIDDEN and its active cues go to
     * [cues] for the shared caption renderer; the element view lets the browser draw it.
     */
    private fun applyCaptions() {
        val tracks = video.textTracks
        for (i in 0 until tracks.length) {
            val track = tracks[i] ?: continue
            val on = captionsOn && i == 0
            track.mode = when {
                !on -> TextTrackMode.DISABLED
                drawsInCanvas.value -> TextTrackMode.HIDDEN
                else -> TextTrackMode.SHOWING
            }
            if (on && track !== cueTrack) {
                cueTrack = track
                track.addEventListener("cuechange") { if (track === cueTrack) publishCues(track) }
            }
        }
        if (!captionsOn || !drawsInCanvas.value) _cues.value = emptyList()
    }

    private fun publishCues(track: TextTrack) {
        if (!captionsOn || !drawsInCanvas.value || track.mode != TextTrackMode.HIDDEN) return
        val active = track.activeCues ?: return run { _cues.value = emptyList() }
        _cues.value = (0 until active.length).mapNotNull { i ->
            active[i]?.let { cue -> cueText(cue).takeIf { it.isNotBlank() }?.let { CaptionCue(AnnotatedString(it)) } }
        }
    }
}

private fun isCrossOrigin(url: String): Boolean = URL(url, window.location.href).origin != window.location.origin

/** A VTT cue's text with its markup tags removed; the bindings have no VTTCue. */
@OptIn(ExperimentalWasmJsInterop::class)
private fun cueText(cue: TextTrackCue): String =
    cue.unsafeCast<TextCue>().text.orEmpty().replace(Regex("<[^>]*>"), "")

@OptIn(ExperimentalWasmJsInterop::class)
private external interface TextCue : JsAny {
    val text: String?
}
