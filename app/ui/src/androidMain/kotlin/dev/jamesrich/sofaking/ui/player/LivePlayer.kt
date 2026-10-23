package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AdBreakNow
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.PlaybackPreferences
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.draw.clipToBounds

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val context = LocalContext.current
    val player = remember(captionSync) { ExoLivePlayer(context.applicationContext, captionSync) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The app's one player on Media3. The server relays HLS or continuous MPEG-TS that can start mid-GOP; the
 * CEA-608/708 formats are declared, text renders through [captionSync] ahead of the picture, playback start waits
 * for its buffer, and the preferred caption track is chosen again on every track change (ad breaks drop 708).
 */
@OptIn(UnstableApi::class)
internal class ExoLivePlayer(private val context: Context, override val captionSync: CaptionSync) : LivePlayer {
    private val tuning = PlaybackTuning()

    @Volatile
    private var preferences = PlaybackPreferences()

    val exo: ExoPlayer = run {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setTsSubtitleFormats(captionFormats())
        // A decoder that fails to start hands over to the next one the selector lists.
        val renderers = CaptionSyncRenderersFactory(context, captionSync)
            .setMediaCodecSelector(tuning.codecSelector)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(CaptionSyncLoadControl(captionSync, tuning))
            // The audible player yields to Assistant and other apps the way the MediaSession promises; see [volume].
            .setAudioAttributes(MEDIA_AUDIO, true)
            // Unplugged headphones pause the stream rather than move it to the speaker.
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
            .build()
            .apply {
                trackSelectionParameters = captionParameters(trackSelectionParameters, enabled = false)
                playWhenReady = true
            }
    }

    private val pauseClock = LivePauseClock()

    /** The held item's [PlaybackStream.seekable]; Media3 calls a sliding live window seekable too. */
    private var seekableItem = false
    private val _status = MutableStateFlow(PlayerStatus())
    private val _errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    private val _cues = MutableStateFlow<List<CaptionCue>>(emptyList())
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())

    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()
    override val errors: SharedFlow<PlayerError> = _errors.asSharedFlow()
    override val cues: StateFlow<List<CaptionCue>> = _cues.asStateFlow()
    override val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _status.value = PlayerStatus(stateOf(exo.playbackState), exo.isPlaying, exo.playWhenReady)
        }

        override fun onTracksChanged(tracks: Tracks) {
            preferCaptionTrack(exo)
            // One track is no choice; the card then leaves the picker out.
            _audioTracks.value = audioTracksOf(tracks).takeIf { it.size > 1 }.orEmpty()
        }

        override fun onCues(cueGroup: CueGroup) {
            _cues.value = cueGroup.cues.mapNotNull { it.toCaptionCue() }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Run past the buffer: re-prepare at the live edge rather than report it.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exo.seekToDefaultPosition()
                exo.prepare()
                return
            }
            val gone = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 410
            _errors.tryEmit(PlayerError(error.errorCodeName, streamGone = gone))
        }
    }

    init {
        exo.addListener(listener)
    }

    override val currentChannelId: String?
        get() = exo.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }

    override val currentStreamUrl: String?
        get() = exo.currentMediaItem?.localConfiguration?.uri?.toString()

    override val hasItem: Boolean get() = exo.mediaItemCount > 0

    override val bufferedMs: Long get() = exo.totalBufferedDuration

    override val supportsCaptionLead: Boolean get() = true

    override val hasTextTrack: Boolean
        get() = exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }

    /**
     * Audio focus follows the volume: the muted guide preview holds none, so music or another app keeps playing
     * under it, and the player asks for focus when it becomes audible.
     */
    override var volume: Float
        get() = exo.volume
        set(value) {
            exo.volume = value
            val audible = value > 0f
            if (audible != handlesAudioFocus) {
                handlesAudioFocus = audible
                exo.setAudioAttributes(MEDIA_AUDIO, audible)
            }
        }

    private var handlesAudioFocus = true

    override var playWhenReady: Boolean
        get() = exo.playWhenReady
        set(value) {
            exo.playWhenReady = value
        }

    override fun setCaptionsEnabled(enabled: Boolean) {
        exo.trackSelectionParameters = captionParameters(exo.trackSelectionParameters, enabled)
    }

    override fun selectAudioTrack(id: String) = selectAudioTrack(exo, id)

    override fun streamInfo(): StreamInfo = streamInfoOf(exo.videoFormat, exo.audioFormat)

    override fun setPlaybackPreferences(preferences: PlaybackPreferences) {
        this.preferences = preferences
        tuning.setBuffer(preferences.buffer)
    }

    override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
        // Before the item, so the codec chosen for it is the channel's own.
        tuning.preferSoftware = preferences.decoderFor(stream.channelId) == DecoderPreference.SOFTWARE
        seekableItem = stream.seekable
        exo.setMediaItem(mediaItemFor(stream, nowPlaying?.toMetadata() ?: MediaMetadata.EMPTY))
    }

    override fun replaceStream(stream: PlaybackStream) {
        val item = exo.currentMediaItem ?: return setStream(stream)
        exo.stop()
        seekableItem = stream.seekable
        exo.setMediaItem(item.buildUpon().setUri(stream.url).setMimeType(stream.mimeType).build())
    }

    override fun prepare() = exo.prepare()

    override fun stop() = exo.stop()

    override fun play() {
        if (pauseClock.onPlay(uptimeMs()) && !seekableItem) exo.seekToDefaultPosition()
        exo.play()
    }

    override fun pause() {
        pauseClock.onPause(uptimeMs())
        exo.pause()
    }

    // Until when a pause from the system media session is ignored; see [ignoreSystemPauseForModeSwitch].
    @Volatile
    private var systemPauseIgnoredUntil = 0L

    /**
     * The display is switching modes for the picture's frame rate. Shield-class boxes send a media-session pause as
     * HDMI renegotiates, which would leave a channel or film paused after it starts, so such a pause is ignored for
     * a few seconds. Remote keys reach the player screens directly, so a real press still pauses.
     */
    fun ignoreSystemPauseForModeSwitch() {
        systemPauseIgnoredUntil = uptimeMs() + MODE_SWITCH_PAUSE_WINDOW_MS
    }

    /** Whether a media-session pause now is the display switching modes rather than the viewer. */
    internal fun ignoresSystemPause(): Boolean = uptimeMs() < systemPauseIgnoredUntil

    override val playbackProfile: PlaybackProfile by lazy { media3Profile(context) }

    override val isSeekable: Boolean get() = seekableItem && exo.isCurrentMediaItemSeekable

    override val positionMs: Long get() = if (isSeekable) exo.currentPosition.coerceAtLeast(0L) else 0L

    override val durationMs: Long? get() = exo.duration.takeIf { isSeekable && it != C.TIME_UNSET }

    override val readsAdMarks: Boolean get() = true

    private val adMarks = AdMarks()
    private val adPeriod = Timeline.Period()

    /**
     * The window counts from its playlist's first segment, which slides on every refresh while the picture may still
     * be in a segment the window has dropped; the period's clock stays put, so the marks are kept on it.
     */
    override fun adBreak(): AdBreakNow? {
        if (seekableItem) return null
        val playlist = (exo.currentManifest as? HlsManifest)?.mediaPlaylist ?: return null
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return null
        val windowStartMs = -timeline.getPeriod(exo.currentPeriodIndex, adPeriod).positionInWindowMs
        val segments = playlist.segments
        adMarks.add(
            tags = playlist.tags,
            mediaSequence = playlist.mediaSequence,
            startsMs = segments.map { windowStartMs + it.relativeStartTimeUs / 1_000 },
            durationsMs = segments.map { it.durationUs / 1_000 },
        )
        return adMarks.at(windowStartMs + exo.currentPosition)
    }

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        exo.seekTo(clampSeek(positionMs, durationMs))
    }

    private val mediaSessions = NewestMediaSession(::openSystemSession)

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = mediaSessions.open(onZapStep)

    /**
     * Media3 refuses a second session under an id the process already holds, and each activity has its own player:
     * a sofaking://pair link opened from another app in split screen starts a second one beside the first.
     */
    private fun openSystemSession(onZapStep: (Int) -> Unit): MediaSessionHandle {
        val forwarding = sessionPlayer
        forwarding.onZapStep = onZapStep
        val session = MediaSession.Builder(context, forwarding).setId("player-${sessionIds.incrementAndGet()}").build()
        return object : MediaSessionHandle {
            override fun publish(nowPlaying: NowPlaying) = forwarding.publishNowPlaying(nowPlaying.toMetadata())

            override fun close() {
                session.release()
                forwarding.onZapStep = {}
                forwarding.publishNowPlaying(MediaMetadata.EMPTY)
            }
        }
    }

    /** Made for the first session; it listens to [exo] until the player is released, so later sessions reuse it. */
    private val sessionPlayer by lazy { LivePlayerWrapper(this) }

    /** Live video through media3-ui-compose: `ContentFrame` sizes a SurfaceView to the picture and shows [shutter] after each reset. */
    @Composable
    override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) {
        ContentFrame(
            player = exo,
            modifier = modifier.clipToBounds(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = zoom.contentScale,
            shutter = shutter,
        )
    }

    override fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    private fun stateOf(state: Int) = when (state) {
        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
        Player.STATE_READY -> PlaybackState.READY
        Player.STATE_ENDED -> PlaybackState.ENDED
        else -> PlaybackState.IDLE
    }
}

/** Numbers the process's media sessions, as each needs its own id. */
private val sessionIds = AtomicInteger()

/** Film and TV sound, so the system treats the stream as media with speech and music, not as an unknown sound. */
private val MEDIA_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * The item for a session [stream], tagged with its channel. The MIME type comes from the server's
 * descriptor, so an HLS playlist is opened as HLS and never sniffed as MPEG-TS.
 */
internal fun mediaItemFor(stream: PlaybackStream, metadata: MediaMetadata = MediaMetadata.EMPTY): MediaItem =
    MediaItem.Builder()
        .setMediaId(stream.channelId)
        .setUri(stream.url)
        .setMimeType(stream.mimeType)
        .setMediaMetadata(metadata)
        .build()

/** How long after a display mode switch a media-session pause is taken for HDMI renegotiating. */
private const val MODE_SWITCH_PAUSE_WINDOW_MS = 4_000L
