package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.PlaybackProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.impl.BufferUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

private val log = Logger.withTag("SofaKing")

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val player = remember(captionSync) {
        try {
            VlcLivePlayer(captionSync)
        } catch (e: Throwable) {
            // libVLC is a system install on desktops; without it every tune fails with a clear code.
            log.w(e) { "libVLC not found; playback is unavailable" }
            UnavailableLivePlayer(captionSync, code = "VLC_NOT_INSTALLED")
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The desktop player on libVLC, which plays the relay's MPEG-TS and HLS alike and draws the stream's CEA-608
 * captions into the picture itself. Frames arrive as BGRA buffers and are copied into a Skia bitmap, so Compose
 * draws overlays above the video as on every other platform. libVLC demuxes by content; it has no MIME hint.
 */
internal class VlcLivePlayer(override val captionSync: CaptionSync) : StreamLivePlayer() {
    private val factory = MediaPlayerFactory("--no-video-title-show", "--network-caching=1500", "--quiet")
    private val vlc: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    /**
     * libVLC's stop and release block until its input thread ends, hundreds of milliseconds on a stalled stream,
     * and the app stops on every zap from the UI thread. Every libVLC command runs on this one thread, in order,
     * while [_status] moves at once.
     */
    private val commands = Executors.newSingleThreadExecutor { task -> Thread(task, "libvlc-commands").apply { isDaemon = true } }

    /**
     * Counts the streams [prepare] opens and [stop] ends. A queued stop runs late, and the old stream's callbacks keep
     * coming until it does; they count only while [opened], set by the command that opens a stream, is the latest.
     */
    private val generation = AtomicInteger()

    @Volatile
    private var opened = -1

    private val isCurrent: Boolean get() = opened == generation.get()

    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    override val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    @Volatile
    private var captionsOn = false

    /**
     * Two bitmaps: libVLC's thread copies each frame into [back], then swaps it to the front under [frameLock], which
     * the canvas holds while it draws [image], so a frame is never drawn while it is being written. [frameCount]
     * invalidates the canvas without recomposing.
     */
    private val frameLock = Any()
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var image: ImageBitmap? = null
    private val frameCount = mutableIntStateOf(0)
    private var hasFrame by mutableStateOf(false)

    init {
        vlc.videoSurface().set(factory.videoSurfaces().newVideoSurface(Formats(), Frames(), true))
        vlc.events().addMediaPlayerEventListener(Events())
    }

    override val bufferedMs: Long get() = 0L
    /** libVLC renders captions itself and reports no buffer depth, so there is nothing to lead. */
    override val supportsCaptionLead: Boolean = false
    override val hasTextTrack: Boolean get() = vlc.subpictures().trackCount() > 0

    /** Set again as each audio stream starts, from [Events.audioDeviceChanged]. */
    @Volatile
    override var volume: Float = 1f
        set(value) {
            field = value
            commands.execute { applyVolume() }
        }

    /** On the command thread. */
    private fun applyVolume() {
        vlc.audio().setVolume((volume * 100).roundToInt())
    }

    override var playWhenReady: Boolean
        get() = _status.value.playWhenReady
        set(value) {
            _status.update { it.copy(playWhenReady = value) }
            commands.execute { if (vlc.status().isPlayable) vlc.controls().setPause(!value) }
        }

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        commands.execute { applyCaptions() }
    }

    override fun selectAudioTrack(id: String) {
        val track = id.toIntOrNull() ?: return
        commands.execute {
            vlc.audio().setTrack(track)
            readAudioTracks()
        }
    }

    /** libVLC describes the tracks of the media it has opened; nothing before the first frame. */
    override fun streamInfo(): StreamInfo {
        val info = vlc.media().info() ?: return StreamInfo()
        val video = info.videoTracks().firstOrNull()
        val audio = info.audioTracks().firstOrNull()
        return StreamInfo(
            videoCodec = video?.codecName()?.takeIf { it.isNotBlank() }?.uppercase(),
            width = video?.width()?.takeIf { it > 0 },
            height = video?.height()?.takeIf { it > 0 },
            frameRate = video?.let { if (it.frameRateBase() > 0) it.frameRate().toFloat() / it.frameRateBase() else null },
            bitrateBps = video?.bitRate()?.takeIf { it > 0 },
            audioCodec = audio?.codecName()?.takeIf { it.isNotBlank() }?.uppercase(),
        )
    }

    /** The audio tracks libVLC lists, without its "Disable" entry; on the command thread, since the list is read from libVLC. */
    private fun readAudioTracks() {
        if (!isCurrent) return
        val current = vlc.audio().track()
        val tracks = vlc.audio().trackDescriptions().filter { it.id() >= 0 }
        _audioTracks.value = if (tracks.size > 1) tracks.map { AudioTrack(it.id().toString(), it.description(), it.id() == current) } else emptyList()
    }

    override fun prepare() {
        val url = stream?.url ?: return
        _status.update { it.copy(state = PlaybackState.BUFFERING) }
        val start = playWhenReady
        val opening = generation.incrementAndGet()
        commands.execute {
            opened = opening
            if (start) vlc.media().play(url) else vlc.media().startPaused(url)
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        commands.execute { vlc.controls().stop() }
        hasFrame = false
        _audioTracks.value = emptyList()
        _status.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
    }

    // libVLC answers these from its own state without blocking, so they are read on the caller's thread.
    /** libVLC decodes nearly every container and codec in software; it does not map HDR to the screen. */
    override val playbackProfile = PlaybackProfile(
        containers = listOf("mp4", "mkv", "webm", "ts", "avi", "mov"),
        videoCodecs = listOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg2video", "mpeg4", "vc1"),
        audioCodecs = listOf("aac", "ac3", "eac3", "dts", "truehd", "mp3", "mp2", "opus", "vorbis", "flac"),
        maxHeight = 2160,
    )

    override val isSeekable: Boolean get() = stream?.seekable == true && vlc.status().isSeekable

    override val positionMs: Long get() = if (isSeekable) vlc.status().time().coerceAtLeast(0L) else 0L

    override val durationMs: Long? get() = vlc.status().length().takeIf { isSeekable && it > 0L }

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        val target = clampSeek(positionMs, durationMs)
        commands.execute { vlc.controls().setTime(target) }
    }

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = NoMediaSession

    @Composable
    override fun Video(modifier: Modifier, shutter: @Composable () -> Unit) {
        Box(modifier) {
            Canvas(Modifier.matchParentSize()) {
                frameCount.intValue
                synchronized(frameLock) {
                    val frame = image ?: return@Canvas
                    val scale = min(size.width / frame.width, size.height / frame.height)
                    val drawn = Size(frame.width * scale, frame.height * scale)
                    val origin = Offset((size.width - drawn.width) / 2, (size.height - drawn.height) / 2)
                    drawImage(
                        frame,
                        dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                        dstSize = IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()),
                    )
                }
            }
            if (!hasFrame) shutter()
        }
    }

    override fun release() {
        // After the player is released no frame can arrive, so the bitmaps go on the same thread, after it.
        commands.execute {
            vlc.release()
            factory.release()
            synchronized(frameLock) {
                image = null
                front?.close()
                back?.close()
                front = null
                back = null
            }
        }
        commands.shutdown()
    }

    /** On the command thread, as it sets libVLC's track; the stream's playing event applies it to each new stream. */
    private fun applyCaptions() {
        if (!isCurrent) return
        val subs = vlc.subpictures()
        if (!captionsOn) {
            subs.setTrack(-1)
            return
        }
        subs.trackDescriptions().firstOrNull { it.id() >= 0 }?.let { subs.setTrack(it.id()) }
    }

    private inner class Formats : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            val info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            // The front keeps showing until a frame of the new size replaces it.
            synchronized(frameLock) {
                back?.close()
                back = Bitmap().apply { allocPixels(info) }
            }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private inner class Frames : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) = Unit

        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(
            mediaPlayer: MediaPlayer,
            nativeBuffers: Array<out ByteBuffer>,
            bufferFormat: BufferFormat,
            displayWidth: Int,
            displayHeight: Int,
        ) {
            if (!isCurrent) return
            // Only libVLC's thread writes the back bitmap and the canvas never reads it, so the copy needs no lock.
            val target = synchronized(frameLock) { back } ?: return
            val info = target.imageInfo
            val source = nativeBuffers[0]
            if (source.capacity() < info.minRowBytes * info.height) return
            val into = target.peekPixels() ?: return
            Pixmap.make(info, BufferUtil.getPointerFromByteBuffer(source), info.minRowBytes).use { it.readPixels(into) }
            target.notifyPixelsChanged()
            synchronized(frameLock) {
                val old = front
                back = if (old != null && old.imageInfo == info) {
                    old
                } else {
                    old?.close()
                    Bitmap().apply { allocPixels(info) }
                }
                front = target
                image = target.asComposeImageBitmap()
            }
            hasFrame = true
            frameCount.intValue++
        }
    }

    /** libVLC calls these on its own threads, which must not call back into it; libVLC calls go to [commands]. */
    private inner class Events : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            _status.update { it.copy(state = PlaybackState.READY, isPlaying = true) }
            commands.execute {
                applyCaptions()
                readAudioTracks()
            }
        }

        /**
         * libVLC reports the device as each audio stream starts. On PulseAudio and PipeWire a volume set while no stream
         * exists is lost, and the new stream takes the volume the server last saw for the app, often the guide's muted
         * preview, so the player's volume is set again on the new stream.
         */
        override fun audioDeviceChanged(mediaPlayer: MediaPlayer, audioDevice: String?) {
            commands.execute { applyVolume() }
        }

        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
            if (type == TrackType.AUDIO) commands.execute { readAudioTracks() }
        }

        override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
            if (type == TrackType.AUDIO) commands.execute { readAudioTracks() }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            _status.update { it.copy(state = PlaybackState.READY, isPlaying = false) }
        }

        /** libVLC reports its cache filling throughout playback; only a cache short of full before playing is buffering. */
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            if (!isCurrent) return
            _status.update {
                when {
                    newCache >= 100f -> it.copy(state = PlaybackState.READY)
                    it.isPlaying -> it
                    else -> it.copy(state = PlaybackState.BUFFERING)
                }
            }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            _status.update { it.copy(state = PlaybackState.ENDED, isPlaying = false) }
        }

        override fun error(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            _status.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
            _errors.tryEmit(PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED))
        }
    }
}

/** No system media session on desktops yet; metadata goes nowhere and media keys reach the window as keys. */
internal object NoMediaSession : MediaSessionHandle {
    override fun publish(nowPlaying: NowPlaying) = Unit

    override fun close() = Unit
}
