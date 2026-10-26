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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.PlaybackPreferences
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.runCatchingCancellable
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
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private val log = Logger.withTag("SofaKing")

/**
 * Makes the desktop player. Tests that run the whole app swap in one without libVLC, since where it is not installed
 * its discovery walks the library directories, which on a CI runner takes many minutes.
 */
internal var newDesktopLivePlayer: (CaptionSync) -> LivePlayer = { VlcLivePlayer(it) }

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val player = remember(captionSync) { newDesktopLivePlayer(captionSync) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * Whether vlcj's discovery found libVLC, looked for once per process: vlcj remembers only a find, so where libVLC is
 * missing every player would walk the library directories again.
 */
private val libVlcDiscovered: Boolean by lazy { NativeDiscovery().discover() }

/**
 * libVLC's factory for one player. Discovery only adds the directory it found to the search path, so without a find
 * the system's loader still gets its try; where neither finds libVLC, this throws.
 */
private fun newVlcFactory(): MediaPlayerFactory {
    libVlcDiscovered
    return MediaPlayerFactory(null as NativeDiscovery?, "--no-video-title-show", "--network-caching=1500", "--quiet")
}

/**
 * The desktop player on libVLC, which plays the relay's MPEG-TS and HLS alike and draws the stream's CEA-608
 * captions into the picture itself. Frames arrive as BGRA buffers and are copied into a Skia bitmap, so Compose
 * draws overlays above the video as on every other platform. libVLC demuxes by content; it has no MIME hint.
 *
 * libVLC is found and loaded by [openVlc] on the command thread, not the UI's: where it is not installed, the search
 * takes seconds on a desktop and many minutes on a CI runner. Without it, every prepare fails with `VLC_NOT_INSTALLED`,
 * so the screens show their failure cards and the rest of the app works.
 */
internal class VlcLivePlayer(
    override val captionSync: CaptionSync,
    private val commands: VlcCommands = VlcCommands(),
    private val tracks: VlcTracks = VlcTracks(commands),
    private val openVlc: () -> MediaPlayerFactory = ::newVlcFactory,
) : StreamLivePlayer(),
    PlayerTracks by tracks {
    /** The player, inside commands, which run only once it exists. */
    private val vlc: EmbeddedMediaPlayer get() = commands.vlc
    private val player: EmbeddedMediaPlayer? get() = commands.player
    private val isCurrent: Boolean get() = commands.isCurrent

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
    private val mutableVideoSize = MutableStateFlow<IntSize?>(null)
    override val videoSize: StateFlow<IntSize?> = mutableVideoSize.asStateFlow()

    init {
        commands.execute {
            // libVLC is a system install on desktops; without it every tune fails with a clear code. A missing library
            // is a LinkageError, and vlcj throws its own exceptions for one that loads but does not start.
            try {
                runCatchingCancellable(::open).onFailure(::unavailable)
            } catch (e: LinkageError) {
                unavailable(e)
            }
        }
    }

    private fun open() {
        val factory = openVlc()
        commands.factory = factory
        val vlc = factory.mediaPlayers().newEmbeddedMediaPlayer()
        vlc.videoSurface().set(factory.videoSurfaces().newVideoSurface(Formats(), Frames(), true))
        vlc.events().addMediaPlayerEventListener(Events())
        commands.player = vlc
    }

    private fun unavailable(failure: Throwable) = log.w(failure) { "libVLC not found; playback is unavailable" }

    override val bufferedMs: Long get() = 0L

    /** libVLC renders captions itself and reports no buffer depth, so there is nothing to lead. */
    override val supportsCaptionLead: Boolean = false

    /** Set again as each audio stream starts, from [Events.audioDeviceChanged]. */
    @Volatile
    override var volume: Float = 1f
        set(value) {
            field = value
            commands.command { applyVolume() }
        }

    override val drawsVolumeControl: Boolean get() = true

    /** On the command thread. */
    private fun applyVolume() {
        vlc.audio().setVolume((volume * VLC_FULL_VOLUME).roundToInt())
    }

    @Volatile
    private var nightListening = false

    override fun setPlaybackPreferences(preferences: PlaybackPreferences) {
        nightListening = preferences.nightListening
    }

    override var playWhenReady: Boolean
        get() = mutableStatus.value.playWhenReady
        set(value) {
            mutableStatus.update { it.copy(playWhenReady = value) }
            commands.command { if (vlc.status().isPlayable) vlc.controls().setPause(!value) }
        }

    override fun prepare() {
        val url = stream?.url ?: return
        mutableStatus.update { it.copy(state = PlaybackState.BUFFERING) }
        val start = playWhenReady
        val opening = commands.generation.incrementAndGet()
        commands.execute {
            if (player == null) {
                if (commands.generation.get() != opening) return@execute
                mutableStatus.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
                mutableErrors.tryEmit(PlayerError(VLC_NOT_INSTALLED))
                return@execute
            }
            commands.opened = opening
            // libVLC's compressor, as a media option, so it applies from the next stream opened.
            val options = if (nightListening) arrayOf(NIGHT_LISTENING_OPTION) else emptyArray()
            if (start) vlc.media().play(url, *options) else vlc.media().startPaused(url, *options)
            if (speed != 1f) vlc.controls().setRate(speed)
        }
    }

    override fun stop() {
        commands.generation.incrementAndGet()
        commands.command { vlc.controls().stop() }
        hasFrame = false
        tracks.clear()
        mutableStatus.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
    }

    // libVLC answers these from its own state without blocking, so they are read on the caller's thread.

    /**
     * libVLC decodes nearly every container and codec in software, at any bit depth; it does not map HDR to the screen.
     */
    override val playbackProfile = PlaybackProfile(
        containers = listOf("mp4", "mkv", "webm", "ts", "avi", "mov"),
        videoCodecs = listOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg2video", "mpeg4", "vc1"),
        audioCodecs = listOf("aac", "ac3", "eac3", "dts", "truehd", "mp3", "mp2", "opus", "vorbis", "flac"),
        maxHeight = 2160,
        deepColor = listOf("h264", "hevc", "vp9", "av1"),
    )

    override val isSeekable: Boolean get() = stream?.seekable == true && player?.status()?.isSeekable == true

    override val positionMs: Long get() = if (isSeekable) player?.status()?.time()?.coerceAtLeast(0L) ?: 0L else 0L

    override val durationMs: Long? get() = player?.status()?.length()?.takeIf { isSeekable && it > 0L }

    /**
     * libVLC reports no buffer depth, but it holds a live HLS stream its live delay behind the newest segment, which
     * it fetches as soon as the playlist lists it; that delay stands in for what it has loaded past the picture.
     */
    override val loadedAheadMs: Long? get() = if (stream?.seekable == false) VLC_LIVE_DELAY_MS else null

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        val target = clampSeek(positionMs, durationMs)
        commands.command { vlc.controls().setTime(target) }
    }

    /** Set again on each new stream, which libVLC would start at normal speed; it keeps the pitch. */
    @Volatile
    private var speed = 1f

    override val speedAdjustable: Boolean get() = true

    override fun setSpeed(rate: Float) {
        speed = rate
        commands.command { vlc.controls().setRate(rate) }
    }

    /** On Linux, MPRIS on the session bus; elsewhere none, which Windows and macOS would need native code for. */
    private val mediaSessions = NewestMediaSession { onZapStep ->
        MprisSession.open(this, onZapStep, APP_NAME) ?: NoMediaSession
    }

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = mediaSessions.open(onZapStep)

    @Composable
    override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) {
        Box(modifier) {
            Canvas(Modifier.matchParentSize().clipToBounds()) {
                frameCount.intValue
                synchronized(frameLock) {
                    val frame = image ?: return@Canvas
                    val scale = zoom.contentScale.computeScaleFactor(
                        Size(frame.width.toFloat(), frame.height.toFloat()),
                        size,
                    )
                    val drawn = Size(frame.width * scale.scaleX, frame.height * scale.scaleY)
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
            commands.player?.release()
            commands.factory?.release()
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

    private inner class Formats : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            val info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            mutableVideoSize.value = IntSize(sourceWidth, sourceHeight)
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
            mutableStatus.update { it.copy(state = PlaybackState.READY, isPlaying = true) }
            commands.command {
                tracks.apply()
                tracks.read()
            }
        }

        /**
         * libVLC reports the device as each audio stream starts. On PulseAudio and PipeWire a volume set while no
         * stream exists is lost, and the new stream takes the volume the server last saw for the app, often the guide's
         * muted preview, so the player's volume is set again on the new stream.
         */
        override fun audioDeviceChanged(mediaPlayer: MediaPlayer, audioDevice: String?) {
            commands.command { applyVolume() }
        }

        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
            if (type == TrackType.AUDIO) commands.command { tracks.read() }
        }

        override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
            if (type == TrackType.AUDIO) commands.command { tracks.read() }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            mutableStatus.update { it.copy(state = PlaybackState.READY, isPlaying = false) }
        }

        /**
         * libVLC reports its cache filling throughout playback; only a cache short of full before playing is buffering.
         */
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            if (!isCurrent) return
            mutableStatus.update {
                when {
                    newCache >= VLC_FULL_CACHE -> it.copy(state = PlaybackState.READY)
                    it.isPlaying -> it
                    else -> it.copy(state = PlaybackState.BUFFERING)
                }
            }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            mutableStatus.update { it.copy(state = PlaybackState.ENDED, isPlaying = false) }
        }

        override fun error(mediaPlayer: MediaPlayer) {
            if (!isCurrent) return
            mutableStatus.update { it.copy(state = PlaybackState.IDLE, isPlaying = false) }
            mutableErrors.tryEmit(PlayerError(PlayerErrorCodes.NETWORK_CONNECTION_FAILED))
        }
    }
}

/** The code a desktop without libVLC fails every tune with; the failure card tells the viewer to install VLC. */
internal const val VLC_NOT_INSTALLED = "VLC_NOT_INSTALLED"

/** The name the desktop's media controls show for the player. */
private const val APP_NAME = "Sofa King"

/** libVLC's default `adaptive-livedelay`: how far behind the newest segment of a live HLS playlist it plays. */
private const val VLC_LIVE_DELAY_MS = 15_000L

/** libVLC's volume for the full level, and the cache it reports once full, both in percent. */
private const val VLC_FULL_VOLUME = 100
private const val VLC_FULL_CACHE = 100f

/** libVLC's dynamic range compressor, with its defaults, for night listening. */
private const val NIGHT_LISTENING_OPTION = ":audio-filter=compressor"
