package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

/** The system media session the full screen holds; [publish] swaps its metadata without touching the stream. */
interface MediaSessionHandle : AutoCloseable {
    fun publish(nowPlaying: NowPlaying)
}

/**
 * The app's one live player, shared by the guide preview and the full screen so OK on the previewed channel
 * continues its stream. Each platform plays the server's HLS or MPEG-TS with its own engine: Media3 on Android,
 * libVLC on desktops, AVPlayer on iOS, a video element on the web. Items are tagged with their channel id and
 * opened with the descriptor's MIME type, never sniffed.
 */
interface LivePlayer {
    val status: StateFlow<PlayerStatus>
    val errors: SharedFlow<PlayerError>

    /** The cues on screen now; empty while captions are off or the stream carries none. */
    val cues: StateFlow<List<CaptionCue>>

    /** The caption lead the full screen sets per channel; engines without a text renderer hook leave it unused. */
    val captionSync: CaptionSync

    /** The channel whose stream the player holds, or null with no item. */
    val currentChannelId: String?

    /** The URL of the stream the player holds, or null with no item. */
    val currentStreamUrl: String?

    /** True while the player holds an item, prepared or stopped. */
    val hasItem: Boolean

    /** How much media is downloaded ahead of the playback position. */
    val bufferedMs: Long

    /** True when the current stream offers a caption track the player can show. */
    val hasTextTrack: Boolean

    var volume: Float
    var playWhenReady: Boolean

    /** Text tracks are disabled rather than unselected, so a zap keeps the choice; set it before the first prepare. */
    fun setCaptionsEnabled(enabled: Boolean)

    /** A new item for [stream], tagged with its channel; [nowPlaying] goes to the media session. Call [prepare] after. */
    fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying? = null)

    /** The held item at [stream]'s new URL, keeping its channel tag and metadata. Call [prepare] after. */
    fun replaceStream(stream: PlaybackStream)

    fun prepare()

    /** Stops and resets the renderers; the item and [playWhenReady] are kept for the next [prepare]. */
    fun stop()

    /** Resumes, at the live edge when the pause outlasted [LIVE_RESUME_AFTER_MS]. */
    fun play()

    fun pause()

    /** Opens the system media session; next and previous from it zap through [onZapStep] (+1 next, -1 previous). */
    fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle

    /** The picture, fitted to [modifier]'s bounds; [shutter] covers it from a reset until the next first frame. */
    @Composable
    fun Video(modifier: Modifier, shutter: @Composable () -> Unit)

    fun release()
}

/** The platform's player for the app's one playback pipeline; remembered for the host's lifetime and released with it. */
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
