package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.PlaybackPreferences
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the player is with its item; a live stream that reaches [ENDED] was closed by the server. */
enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

/** The player's state as the screens read it: one value per change, so a screen never sees half an update. */
@Immutable
data class PlayerStatus(
    val state: PlaybackState = PlaybackState.IDLE,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = true,
)

/**
 * A failure the player reported. [code] names it on the failure card; [streamGone] is HTTP 410 from the media
 * relay (re-read the session rather than wait out the backoff). Falling behind the live window is handled inside
 * the player and never reported.
 */
data class PlayerError(val code: String, val streamGone: Boolean = false)

/** Media3's error-code names, which the other engines report under too so one failure card reads them all. */
object PlayerErrorCodes {
    const val NETWORK_CONNECTION_FAILED = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
    const val NETWORK_CONNECTION_TIMEOUT = "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
    const val BAD_HTTP_STATUS = "ERROR_CODE_IO_BAD_HTTP_STATUS"
    const val PARSING_CONTAINER_UNSUPPORTED = "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
    const val DECODING_FAILED = "ERROR_CODE_DECODING_FAILED"
    const val UNSPECIFIED = "ERROR_CODE_UNSPECIFIED"
}

/** What the system's now-playing surfaces (MediaSession, lock screen, browser media keys) show for a channel. */
@Immutable
data class NowPlaying(val title: String, val subtitle: String, val artworkUrl: String?)

/**
 * One caption cue, positioned by the rules Media3's subtitle painter applies; the constants equal Media3's so
 * the Android player maps cues across unchanged. [text] carries the stream's own styles as span styles.
 */
@Immutable
data class CaptionCue(
    val text: AnnotatedString,
    val line: Float = DIMEN_UNSET,
    val lineType: Int = TYPE_UNSET,
    val lineAnchor: Int = TYPE_UNSET,
    val position: Float = DIMEN_UNSET,
    val positionAnchor: Int = TYPE_UNSET,
) {
    companion object {
        const val DIMEN_UNSET = -Float.MAX_VALUE
        const val TYPE_UNSET = Int.MIN_VALUE
        const val LINE_TYPE_FRACTION = 0
        const val LINE_TYPE_NUMBER = 1
        const val ANCHOR_TYPE_START = 0
        const val ANCHOR_TYPE_MIDDLE = 1
        const val ANCHOR_TYPE_END = 2
    }
}

/**
 * One audio track the stream offers, as the stream card lists it; [id] is the engine's own
 * handle for [selectAudioTrack].
 */
@Immutable
data class AudioTrack(val id: String, val label: String, val selected: Boolean)

/**
 * What the engine knows about the stream it holds, read for the stream card; a field the engine cannot report is
 * null. [bitrateBps] is the video track's declared bitrate, or the whole stream's where that is all the engine has.
 * [audioOutput] is what the engine sends the device's audio output.
 */
@Immutable
data class StreamInfo(
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitrateBps: Int? = null,
    val audioCodec: String? = null,
    val audioOutput: AudioOutput? = null,
)

/**
 * How the audio leaves the app: [PassedThrough] sends the encoded stream ([codec], when the engine names it) to a
 * receiver or TV that decodes it; [Decoded] sends [channels] channels of PCM, decoded here.
 */
@Immutable
sealed interface AudioOutput {
    data class PassedThrough(val codec: String?) : AudioOutput

    data class Decoded(val channels: Int) : AudioOutput
}

/** The system media session the full screen holds; [publish] swaps its metadata without touching the stream. */
interface MediaSessionHandle : AutoCloseable {
    fun publish(nowPlaying: NowPlaying)

    /** The platform's own session object behind the handle, where one exists (Media3's `MediaSession` on Android). */
    val platformSession: Any? get() = null
}

/**
 * A player's one system media session, held by the newest [open]. Two pages can hold it for a moment (Up next
 * replaces the page while the pages cross-fade), so opening closes the older handle's session first, and that
 * handle's publish and close then do nothing. Called on the main thread, as the screens' lifecycle effects run.
 */
internal class NewestMediaSession(private val openSession: (onZapStep: (Int) -> Unit) -> MediaSessionHandle) {
    private var newest: MediaSessionHandle? = null

    fun open(onZapStep: (Int) -> Unit): MediaSessionHandle {
        newest?.close()
        val session = openSession(onZapStep)
        return object : MediaSessionHandle {
            override val platformSession: Any? get() = session.platformSession

            override fun publish(nowPlaying: NowPlaying) {
                if (newest === this) session.publish(nowPlaying)
            }

            override fun close() {
                if (newest !== this) return
                newest = null
                session.close()
            }
        }.also { newest = it }
    }
}

/** The caption and audio tracks of the stream a [LivePlayer] holds. */
interface PlayerTracks {
    /** The cues on screen now; empty while captions are off or the stream carries none. */
    val cues: StateFlow<List<CaptionCue>>

    /** True when the current stream offers a caption track the player can show. */
    val hasTextTrack: Boolean

    /**
     * The audio tracks the stream offers, with the playing one marked; empty while there is no choice
     * or the engine cannot list them.
     */
    val audioTracks: StateFlow<List<AudioTrack>>

    /** Plays the audio track [id] from [audioTracks]; the choice lasts for the current item. */
    fun selectAudioTrack(id: String)

    /** What the engine knows about the stream now; the stream card reads it while open. */
    fun streamInfo(): StreamInfo

    /** Text tracks are disabled rather than unselected, so a zap keeps the choice; set it before the first prepare. */
    fun setCaptionsEnabled(enabled: Boolean)
}

/** Moving through a seekable item, its speed, and the ad break in a live one, where the engine can. */
interface PlayerTimeline {
    /**
     * True while the item is a [PlaybackStream.seekable] one the engine can move through: a recording, finished or
     * still growing, or a library file. A live channel is never seekable, whatever its playlist allows.
     */
    val isSeekable: Boolean get() = false

    /** The position from the start of a seekable item; 0 otherwise. */
    val positionMs: Long get() = 0L

    /**
     * The length of a seekable item so far, which grows while a recording is being made;
     * null when unknown or not seekable.
     */
    val durationMs: Long? get() = null

    /** Moves a seekable item to [positionMs], clamped to its length; does nothing otherwise. */
    fun seekTo(positionMs: Long) = Unit

    /** True where the engine plays faster than normal through [setSpeed], keeping the voices' pitch. */
    val speedAdjustable: Boolean get() = false

    /**
     * Plays at [rate] times normal speed (1 is normal) until set again, across new streams too;
     * ignored where not [speedAdjustable].
     */
    fun setSpeed(rate: Float) = Unit

    /**
     * The ad break playing now, read by the engine itself from the marks the server adds to a live playlist (`AdRun`);
     * null outside one. Only engines with [readsAdMarks] answer.
     */
    fun adBreak(): AdBreakNow? = null

    /**
     * True where the engine reads the playlist's ad marks itself (Media3); the others have the server place
     * the picture by [loadedAheadMs].
     */
    val readsAdMarks: Boolean get() = false

    /**
     * How much of a live stream the engine has loaded past the picture, for the server to place the picture among
     * the ads; null when it cannot say.
     */
    val loadedAheadMs: Long? get() = null
}

/**
 * The app's one live player, shared by the guide preview and the full screen so OK on the previewed channel
 * continues its stream. Each platform plays the server's HLS or MPEG-TS with its own engine: Media3 on Android,
 * libVLC on desktops, AVPlayer on iOS, a video element on the web. Items are tagged with their channel id and
 * opened with the descriptor's MIME type, never sniffed.
 */
interface LivePlayer :
    PlayerTracks,
    PlayerTimeline {
    val status: StateFlow<PlayerStatus>
    val errors: SharedFlow<PlayerError>

    /** The caption lead the full screen sets per channel; engines without a text renderer hook leave it unused. */
    val captionSync: CaptionSync

    /** The channel whose stream the player holds, or null with no item. */
    val currentChannelId: String?

    /** The URL of the stream the player holds, or null with no item. */
    val currentStreamUrl: String?

    /** True while the player holds an item, prepared or stopped. */
    val hasItem: Boolean

    /** How much media is downloaded ahead of the playback position; engines that cannot report it say 0. */
    val bufferedMs: Long

    /**
     * True when the engine renders text through [captionSync] and reports [bufferedMs], so a caption lead can be
     * applied and adjusted; the others show captions where the stream puts them.
     */
    val supportsCaptionLead: Boolean

    /** The engine's loudness, 0 (silent) to 1 (the stream's own level). */
    var volume: Float

    /**
     * True where nothing outside the app turns this player up or down for the viewer (a desktop window, a browser
     * page), so the players draw their own volume control (see [PlayerVolume]); a TV, phone or Apple TV has the
     * system's volume keys instead.
     */
    val drawsVolumeControl: Boolean get() = false

    var playWhenReady: Boolean

    /** The decoder and buffer choices, applied from the next [setStream]; engines without the knobs ignore them. */
    fun setPlaybackPreferences(preferences: PlaybackPreferences) = Unit

    /**
     * A new item for [stream], tagged with its channel; [nowPlaying] goes to the media session. Call [prepare] after.
     */
    fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying? = null)

    /** The held item at [stream]'s new URL, keeping its channel tag and metadata. Call [prepare] after. */
    fun replaceStream(stream: PlaybackStream)

    fun prepare()

    /** Stops and resets the renderers; the item and [playWhenReady] are kept for the next [prepare]. */
    fun stop()

    /**
     * Resumes, at the live edge when a live stream's pause outlasted [LIVE_RESUME_AFTER_MS]; a
     * seekable item resumes where it paused.
     */
    fun play()

    fun pause()

    /**
     * What the engine plays as it is, sent when a library item opens: the server plays a file that fits as it is
     * and copies or converts the rest into HLS. Engines claim only what they decode on every device they run on;
     * the default, MP4 with H.264 and AAC up to 1080p, is what every engine plays.
     */
    val playbackProfile: PlaybackProfile get() = PlaybackProfile(
        listOf("mp4"),
        listOf("h264"),
        listOf("aac"),
        maxHeight = 1080,
    )

    /** Opens the system media session; next and previous from it zap through [onZapStep] (+1 next, -1 previous). */
    fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle

    /**
     * The picture in [modifier]'s bounds as [zoom] says, cropped to them; [shutter] covers it from a
     * reset until the next first frame.
     */
    @Composable
    fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit)

    fun release()
}

/**
 * The platform's player for the app's one playback pipeline; remembered for the host's lifetime and released with it.
 */
@Composable
expect fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer

/** Tracks the live-resume rule for engines that pause by simply stopping the clock. */
class LivePauseClock {
    private var pausedAtMs: Long? = null

    fun onPause(nowMs: Long) {
        if (pausedAtMs == null) pausedAtMs = nowMs
    }

    /** True when the player should jump to the live edge before resuming. */
    fun onPlay(nowMs: Long): Boolean = shouldResumeAtLive(pausedAtMs, nowMs).also { pausedAtMs = null }
}

/** A media session that goes nowhere, for a platform or player without one; media keys reach the window as keys. */
internal object NoMediaSession : MediaSessionHandle {
    override fun publish(nowPlaying: NowPlaying) = Unit

    override fun close() = Unit
}

/** No captions and no audio choice, for a player that plays nothing. */
internal object NoTracks : PlayerTracks {
    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()
    override val hasTextTrack: Boolean = false
    override val audioTracks: StateFlow<List<AudioTrack>> = MutableStateFlow(emptyList<AudioTrack>()).asStateFlow()

    override fun selectAudioTrack(id: String) = Unit

    override fun streamInfo(): StreamInfo = StreamInfo()

    override fun setCaptionsEnabled(enabled: Boolean) = Unit
}
