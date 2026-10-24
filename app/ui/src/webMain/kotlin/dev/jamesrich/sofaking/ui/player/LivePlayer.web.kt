package dev.jamesrich.sofaking.ui.player

import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.milliseconds
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.webgl.WebGLRenderTarget
import androidx.compose.ui.platform.webgl.rememberWebGLRenderTarget
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.HtmlElementView
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.HLS_MIME_TYPE
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.app_name
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.stringResource
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLRenderingContext.Companion.CLAMP_TO_EDGE
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_ATTACHMENT0
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER_COMPLETE
import org.khronos.webgl.WebGLRenderingContext.Companion.LINEAR
import org.khronos.webgl.WebGLRenderingContext.Companion.RGBA
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_2D
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_MAG_FILTER
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_MIN_FILTER
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_WRAP_S
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_WRAP_T
import org.khronos.webgl.WebGLRenderingContext.Companion.UNSIGNED_BYTE
import org.khronos.webgl.WebGLTexture
import org.w3c.dom.CanPlayTypeResult
import org.w3c.dom.DISABLED
import org.w3c.dom.EMPTY
import org.w3c.dom.HIDDEN
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.SHOWING
import org.w3c.dom.TextTrack
import org.w3c.dom.TextTrackCue
import org.w3c.dom.TextTrackMode
import org.w3c.dom.events.Event
import org.w3c.dom.get
import org.w3c.dom.url.URL

private val log = Logger.withTag("SofaKing")

private val HAVE_CURRENT_DATA = HTMLMediaElement.HAVE_CURRENT_DATA

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val appName = stringResource(Res.string.app_name)
    val player = remember(captionSync) { HtmlLivePlayer(captionSync, appName) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The browser player on one `<video>` element, shared by the preview and the full screen as on every platform.
 * Browsers play HLS natively (Safari, recent Chromium) and have no MPEG-TS demuxer, so a TS-only channel reports
 * an unsupported format.
 */
internal class HtmlLivePlayer(override val captionSync: CaptionSync, private val appName: String) : StreamLivePlayer() {
    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        setAttribute("playsinline", "")
        // A cross-origin stream's frames can be drawn into WebGL only when fetched with CORS.
        crossOrigin = "anonymous"
        style.width = "100%"
        style.height = "100%"
        style.backgroundColor = "black"
        style.setProperty("object-fit", "contain")
    }
    private var captionsOn = false
    private var cueTrack: TextTrack? = null
    private val onCueChange = { _: Event -> cueTrack?.let(::publishCues) ?: Unit }
    private val onGesture = { _: Event -> restoreSound() }
    private var loaded = false
    private var awaitingGesture = false

    private val drawsInCanvas = MutableStateFlow(true)
    private val frameSize = MutableStateFlow(IntSize(1, 1))

    /** False from a reset until the new stream's first frame is on screen; rebuffering keeps the last frame. */
    private val hasFrame = MutableStateFlow(false)

    private val _cues = MutableStateFlow<List<CaptionCue>>(emptyList())
    override val cues: StateFlow<List<CaptionCue>> = _cues.asStateFlow()

    /** Browsers expose a stream's audio tracks only behind flags, so the card never offers a choice here. */
    override val audioTracks: StateFlow<List<AudioTrack>> = MutableStateFlow(emptyList<AudioTrack>()).asStateFlow()

    init {
        video.addEventListener("playing") {
            mutableStatus.update { it.copy(state = PlaybackState.READY, isPlaying = true) }
        }
        video.addEventListener("pause") { mutableStatus.update { it.copy(isPlaying = false) } }
        video.addEventListener("waiting") { mutableStatus.update { it.copy(state = PlaybackState.BUFFERING) } }
        video.addEventListener("canplay") { mutableStatus.update { it.copy(state = PlaybackState.READY) } }
        video.addEventListener("ended") {
            mutableStatus.update { it.copy(state = PlaybackState.ENDED, isPlaying = false) }
        }
        // The element shows its first frame itself; in the canvas it is on screen once drawn.
        video.addEventListener("loadeddata") { if (!drawsInCanvas.value) hasFrame.value = true }
        video.addEventListener("loadedmetadata") {
            loaded = true
            updateFrameSize()
            applyCaptions()
        }
        video.addEventListener("resize") { updateFrameSize() }
        video.textTracks.addEventListener("addtrack") { applyCaptions() }
        // Capture phase, so the press counts even when the app consumes it.
        document.addEventListener("keydown", onGesture, true)
        document.addEventListener("pointerdown", onGesture, true)
        video.addEventListener("error") {
            // A cross-origin HLS stream that fails before loading may come from a server that sends no CORS headers
            // on media: play it again as a plain element, which needs none. TS fails for its format and reports.
            val current = stream
            val crossOriginHls = current?.mimeType == HLS_MIME_TYPE && isCrossOrigin(current.url)
            if (drawsInCanvas.value && !loaded && crossOriginHls) {
                video.crossOrigin = null
                useElement()
                prepare()
                return@addEventListener
            }
            mutableStatus.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
            val playable = current != null && video.canPlayType(current.mimeType) != CanPlayTypeResult.EMPTY
            mutableErrors.tryEmit(mediaElementError(video.error?.code?.toInt(), playable, loaded))
        }
    }

    override val bufferedMs: Long get() = 0L
    override val supportsCaptionLead: Boolean get() = false
    override val hasTextTrack: Boolean get() = video.textTracks.length > 0

    override var volume: Float
        get() = video.volume.toFloat()
        set(value) {
            video.volume = value.toDouble()
            video.muted = value == 0f || awaitingGesture
        }

    override val drawsVolumeControl: Boolean get() = true

    override var playWhenReady: Boolean
        get() = mutableStatus.value.playWhenReady
        set(value) {
            mutableStatus.update { it.copy(playWhenReady = value) }
            if (value) startPlayback() else video.pause()
        }

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        applyCaptions()
    }

    override fun selectAudioTrack(id: String) = Unit

    /** The element reports only the picture size. */
    override fun streamInfo(): StreamInfo = StreamInfo(
        width = video.videoWidth.takeIf {
            it > 0
        },
        height = video.videoHeight.takeIf { it > 0 },
    )

    override fun prepare() {
        val url = stream?.url ?: return
        mutableStatus.update { it.copy(state = PlaybackState.BUFFERING) }
        loaded = false
        hasFrame.value = false
        video.src = url
        if (playWhenReady) startPlayback()
    }

    override fun stop() {
        video.pause()
        video.removeAttribute("src")
        video.load()
        cueTrack?.removeEventListener("cuechange", onCueChange)
        cueTrack = null
        _cues.value = emptyList()
        hasFrame.value = false
        mutableStatus.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
    }

    /** What current browsers' video elements all play: MP4 and WebM with H.264, VP8 or VP9. */
    override val playbackProfile = PlaybackProfile(
        containers = listOf("mp4", "webm"),
        videoCodecs = listOf("h264", "vp8", "vp9"),
        audioCodecs = listOf("aac", "mp3", "opus", "vorbis", "flac"),
        maxHeight = 2160,
    )

    override val isSeekable: Boolean get() = stream?.seekable == true && loaded && video.seekable.length > 0

    override val positionMs: Long get() = if (isSeekable) (video.currentTime * 1000).toLong().coerceAtLeast(0L) else 0L

    /** The end of the buffered range the picture is in, less the picture's time. */
    override val loadedAheadMs: Long?
        get() {
            if (stream?.seekable != false || !loaded) return null
            val now = video.currentTime
            val buffered = video.buffered
            for (i in 0 until buffered.length) {
                if (now >= buffered.start(i) && now <= buffered.end(i)) {
                    return ((buffered.end(i) - now) * 1000).toLong().coerceAtLeast(0L)
                }
            }
            return null
        }

    /** A finished file reports its duration; a growing recording reports Infinity, so its seekable end stands in. */
    override val durationMs: Long?
        get() {
            if (!isSeekable) return null
            val seconds =
                video.duration.takeIf { it.isFinite() && it > 0 } ?: video.seekable.end(video.seekable.length - 1)
            return (seconds * 1000).toLong().takeIf { it > 0L }
        }

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        val target = clampSeek(positionMs, durationMs)
        video.currentTime = target.milliseconds.toDouble(DurationUnit.SECONDS)
    }

    override val speedAdjustable: Boolean get() = true

    /** The default rate is what each new source starts at; browsers keep the pitch. */
    override fun setSpeed(rate: Float) {
        video.defaultPlaybackRate = rate.toDouble()
        video.playbackRate = rate.toDouble()
    }

    /**
     * The page title and the browser's Media Session: its metadata reaches the OS media controls, and their media
     * keys come back here. Browsers without the API keep the title alone.
     */
    private val mediaSessions = NewestMediaSession(::openBrowserSession)

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = mediaSessions.open(onZapStep)

    private fun openBrowserSession(onZapStep: (Int) -> Unit): MediaSessionHandle {
        setMediaAction("play") { play() }
        setMediaAction("pause") { pause() }
        setMediaAction("nexttrack") { onZapStep(1) }
        setMediaAction("previoustrack") { onZapStep(-1) }
        return object : MediaSessionHandle {
            override fun publish(nowPlaying: NowPlaying) {
                document.title = "${nowPlaying.title} - $appName"
                setMediaMetadata(nowPlaying.title, nowPlaying.subtitle, nowPlaying.artworkUrl)
            }

            override fun close() {
                document.title = appName
                for (action in MEDIA_ACTIONS) clearMediaAction(action)
                clearMediaMetadata()
            }
        }
    }

    /**
     * Draws each decoded frame into a WebGL texture inside Compose's canvas, so overlays draw over the picture as on
     * other platforms. An element view would sit above the canvas and hide them; it remains the fallback when the
     * browser has no WebGL2 or the stream is cross-origin without CORS, which taints the frames. A frame is drawn
     * when the video presents one (`requestVideoFrameCallback`), so a paused or stalled stream costs nothing.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) {
        val showsFrame by hasFrame.collectAsState()
        val inCanvas by drawsInCanvas.collectAsState()
        val size by frameSize.collectAsState()
        val target = if (inCanvas) rememberWebGLRenderTarget(size) else null
        LaunchedEffect(target) {
            if (target == null) return@LaunchedEffect
            val frames = VideoFrames(target.webGLContext)
            try {
                // A new target starts empty, so the current frame goes in at once, even while paused.
                while (true) {
                    if (video.readyState >= HAVE_CURRENT_DATA && video.videoWidth > 0 && video.videoHeight > 0) {
                        // A browser's own errors, WebGL's among them, are Throwables but not Exceptions here.
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            var drawn = false
                            target.render { drawn = frames.draw(video, target) }
                            if (drawn) hasFrame.value = true
                        } catch (e: Throwable) {
                            log.w(e) { "drawing video frames in the canvas failed; using the element" }
                            hasFrame.value = video.readyState >= HAVE_CURRENT_DATA
                            useElement()
                            break
                        }
                    }
                    awaitVideoFrame(video)
                }
            } finally {
                frames.release()
            }
        }
        LaunchedEffect(zoom) { video.style.setProperty("object-fit", zoom.objectFit) }
        Box(modifier.background(Color.Black).clipToBounds()) {
            if (target != null) {
                Image(
                    target.painter,
                    contentDescription = null,
                    contentScale = zoom.contentScale,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                HtmlElementView(factory = { video }, modifier = Modifier.matchParentSize())
            }
            if (!showsFrame) shutter()
        }
    }

    private fun useElement() {
        drawsInCanvas.value = false
        applyCaptions()
    }

    override fun release() {
        stop()
        document.removeEventListener("keydown", onGesture, true)
        document.removeEventListener("pointerdown", onGesture, true)
    }

    /**
     * Browsers refuse autoplay with sound until the page has had a user gesture. A refused play() is retried muted,
     * which they allow, and the sound returns on the first key press or pointer press.
     */
    private fun startPlayback() {
        video.play().unsafeCast<PlayPromise>().catch { error ->
            if (error.unsafeCast<DomError>().name == "NotAllowedError" && !video.muted) {
                awaitingGesture = true
                video.muted = true
                video.play()
            }
            null
        }
    }

    private fun restoreSound() {
        if (!awaitingGesture) return
        awaitingGesture = false
        video.muted = video.volume == 0.0
    }

    private fun updateFrameSize() {
        if (video.videoWidth > 0 && video.videoHeight > 0) {
            frameSize.value = IntSize(video.videoWidth, video.videoHeight)
        }
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
                cueTrack?.removeEventListener("cuechange", onCueChange)
                cueTrack = track
                track.addEventListener("cuechange", onCueChange)
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

/**
 * Copies the video's current frame into a texture of the frame's own size, then scales it into a render target. A
 * frame can change size before `videoWidth` reports it (a variant switch), so it is never written into a texture of
 * an assumed size. GL objects are created on the first [draw], inside a render, so Compose's GL state stays known.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class VideoFrames(private val gl: WebGLRenderingContext) {
    private var texture: WebGLTexture? = null
    private var source: WebGLFramebuffer? = null

    /** Returns false, drawing nothing, while the browser has no frame to hand over yet. */
    fun draw(video: HTMLVideoElement, target: WebGLRenderTarget): Boolean {
        val texture = texture ?: createTexture().also { texture = it }
        gl.bindTexture(TEXTURE_2D, texture)
        gl.texImage2D(TEXTURE_2D, 0, RGBA, RGBA, UNSIGNED_BYTE, video)
        gl.bindTexture(TEXTURE_2D, null)
        val source = source ?: gl.createFramebuffer().also { fb ->
            gl.bindFramebuffer(READ_FRAMEBUFFER, fb)
            gl.framebufferTexture2D(READ_FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0)
            source = fb
        }
        gl.bindFramebuffer(READ_FRAMEBUFFER, source)
        if (gl.checkFramebufferStatus(READ_FRAMEBUFFER) != FRAMEBUFFER_COMPLETE) {
            gl.bindFramebuffer(FRAMEBUFFER, target.framebuffer)
            return false
        }
        gl.bindFramebuffer(DRAW_FRAMEBUFFER, target.framebuffer)
        // The target's origin is bottom-left and the frame's first row is its top, so the blit flips it.
        gl.unsafeCast<WebGL2Blit>().blitFramebuffer(
            0, 0, video.videoWidth, video.videoHeight,
            0, target.size.height, target.size.width, 0,
            COLOR_BUFFER_BIT, LINEAR,
        )
        gl.bindFramebuffer(FRAMEBUFFER, target.framebuffer)
        return true
    }

    fun release() {
        source?.let(gl::deleteFramebuffer)
        texture?.let(gl::deleteTexture)
        source = null
        texture = null
    }

    private fun createTexture(): WebGLTexture {
        val created = gl.createTexture() ?: error("gl.createTexture() returned null")
        gl.bindTexture(TEXTURE_2D, created)
        gl.texParameteri(TEXTURE_2D, TEXTURE_MIN_FILTER, LINEAR)
        gl.texParameteri(TEXTURE_2D, TEXTURE_MAG_FILTER, LINEAR)
        gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_S, CLAMP_TO_EDGE)
        gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_T, CLAMP_TO_EDGE)
        gl.bindTexture(TEXTURE_2D, null)
        return created
    }
}

private const val READ_FRAMEBUFFER = 0x8CA8
private const val DRAW_FRAMEBUFFER = 0x8CA9

/** WebGL2's blit, which the WebGL 1 bindings lack; [rememberWebGLRenderTarget] only hands out WebGL2 contexts. */
private external interface WebGL2Blit : JsAny {
    fun blitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int,
        filter: Int,
    )
}

/** The promise play() returns; the bindings type it differently on js and wasm. */
private external interface PlayPromise : JsAny {
    fun catch(onRejected: (JsAny) -> JsAny?): JsAny
}

private external interface DomError : JsAny {
    val name: String
}

/**
 * Suspends until [video] presents its next frame. Browsers without `requestVideoFrameCallback` (Firefox before
 * 132) wait for the next display frame instead.
 */
private suspend fun awaitVideoFrame(video: HTMLVideoElement) {
    if (!hasVideoFrameCallback(video)) {
        withFrameNanos { }
        return
    }
    suspendCancellableCoroutine { continuation ->
        val handle = requestVideoFrame(video) { continuation.resumeWith(Result.success(Unit)) }
        continuation.invokeOnCancellation { cancelVideoFrame(video, handle) }
    }
}

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun hasVideoFrameCallback(video: HTMLVideoElement): Boolean = js("'requestVideoFrameCallback' in video")

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun requestVideoFrame(video: HTMLVideoElement, onFrame: () -> Unit): Int =
    js("video.requestVideoFrameCallback(function () { onFrame(); })")

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun cancelVideoFrame(video: HTMLVideoElement, handle: Int) {
    js("video.cancelVideoFrameCallback(handle);")
}

private val MEDIA_ACTIONS = listOf("play", "pause", "nexttrack", "previoustrack")

/** An action the browser doesn't know throws; it is skipped, as the spec advises. */
// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun setMediaAction(action: String, handler: () -> Unit) {
    js(
        "if ('mediaSession' in navigator) { " +
            "try { navigator.mediaSession.setActionHandler(action, function () { handler(); }); } catch (e) {} }",
    )
}

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun clearMediaAction(action: String) {
    js(
        "if ('mediaSession' in navigator) { " +
            "try { navigator.mediaSession.setActionHandler(action, null); } catch (e) {} }",
    )
}

// detekt sees no use of the parameters: the JavaScript in js() reads them by name.
@Suppress("UnusedParameter")
private fun setMediaMetadata(title: String, artist: String, artworkUrl: String?) {
    js(
        "if ('mediaSession' in navigator) { navigator.mediaSession.metadata = " +
            "new MediaMetadata({ title: title, artist: artist, artwork: artworkUrl ? [{ src: artworkUrl }] : [] }); }",
    )
}

private fun clearMediaMetadata() {
    js("if ('mediaSession' in navigator) { navigator.mediaSession.metadata = null; }")
}

private fun isCrossOrigin(url: String): Boolean = URL(url, window.location.href).origin != window.location.origin

/** A VTT cue's text with its markup tags removed; the bindings have no VTTCue. */
private fun cueText(cue: TextTrackCue): String = cue.unsafeCast<TextCue>().text.orEmpty().replace(cueMarkup, "")

private val cueMarkup = Regex("<[^>]*>")

private external interface TextCue : JsAny {
    val text: String?
}

/** The element's `object-fit` for [VideoZoom], for the fallback that shows the element itself. */
private val VideoZoom.objectFit: String
    get() = when (this) {
        VideoZoom.FIT -> "contain"
        VideoZoom.FILL -> "cover"
        VideoZoom.STRETCH -> "fill"
    }
