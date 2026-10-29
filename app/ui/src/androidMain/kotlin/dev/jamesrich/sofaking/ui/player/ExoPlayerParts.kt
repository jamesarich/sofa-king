package dev.jamesrich.sofaking.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.ui.uptimeMs
import dev.jamesrich.sofaking.visualizer.AudioAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.microseconds

/**
 * The Media3 player for [captionSync]: the server relays HLS or continuous MPEG-TS that can start mid-GOP, so
 * non-IDR keyframes are allowed and the CEA-608/708 formats are declared; decoders come from [tuning]'s selector.
 */
@OptIn(UnstableApi::class)
internal fun newExoPlayer(
    context: Context,
    captionSync: CaptionSync,
    tuning: PlaybackTuning,
    tap: AudioAnalyzer? = null,
): ExoPlayer {
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        .setTsSubtitleFormats(captionFormats())
    // A decoder that fails to start hands over to the next one the selector lists.
    val renderers = CaptionSyncRenderersFactory(context, captionSync, tap)
        .setMediaCodecSelector(tuning.codecSelector)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderers)
        .setLoadControl(CaptionSyncLoadControl(captionSync, tuning, defaultDelegate(context.isLowRamDevice())))
        // Audio focus and the unplugged-headphones pause start off and follow the volume; see [ExoLivePlayer.volume].
        .setAudioAttributes(MEDIA_AUDIO, false)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            trackSelectionParameters = captionParameters(trackSelectionParameters, enabled = false)
            playWhenReady = true
        }
}

/** The caption and audio tracks of [exo]'s stream; the player's listener hands them each track change and cue. */
@OptIn(UnstableApi::class)
internal class ExoTracks(private val exo: ExoPlayer) : PlayerTracks {
    private val _cues = MutableStateFlow<List<CaptionCue>>(emptyList())
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    override val cues: StateFlow<List<CaptionCue>> = _cues.asStateFlow()
    override val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    /** What the audio sink last opened its output with; Media3 reports it each time it opens one. */
    @Volatile private var audioOutput: AudioOutput? = null

    init {
        exo.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onAudioTrackInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    audioTrackConfig: AudioSink.AudioTrackConfig,
                ) {
                    audioOutput = audioOutputOf(audioTrackConfig.encoding, audioTrackConfig.channelConfig)
                }
            },
        )
    }

    override val hasTextTrack: Boolean
        get() = exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }

    override fun setCaptionsEnabled(enabled: Boolean) {
        exo.trackSelectionParameters = captionParameters(exo.trackSelectionParameters, enabled)
    }

    override fun selectAudioTrack(id: String) = selectAudioTrack(exo, id)

    override fun streamInfo(): StreamInfo = streamInfoOf(exo.videoFormat, exo.audioFormat).copy(
        audioOutput = audioOutput.takeIf {
            exo.audioFormat != null
        },
    )

    /** The preferred caption track is chosen again on every track change, as ad breaks drop 708. */
    fun onTracksChanged(tracks: Tracks) {
        preferCaptionTrack(exo)
        // One track is no choice; the card then leaves the picker out.
        _audioTracks.value = audioTracksOf(tracks).takeIf { it.size > 1 }.orEmpty()
    }

    fun onCues(cueGroup: CueGroup) {
        _cues.value = cueGroup.cues.mapNotNull { it.toCaptionCue() }
    }
}

/** Moving through [exo]'s seekable item, and the ad break its live playlist marks. */
@OptIn(UnstableApi::class)
internal class ExoTimeline(private val exo: ExoPlayer) : PlayerTimeline {
    /** The held item's [PlaybackStream.seekable]; Media3 calls a sliding live window seekable too. */
    var seekableItem = false

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
            startsMs = segments.map { windowStartMs + it.relativeStartTimeUs.microseconds.inWholeMilliseconds },
            durationsMs = segments.map { it.durationUs.microseconds.inWholeMilliseconds },
        )
        return adMarks.at(windowStartMs + exo.currentPosition)
    }

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        exo.seekTo(clampSeek(positionMs, durationMs))
    }

    /** Media3 keeps the speed across items and stretches the audio without changing its pitch. */
    override val speedAdjustable: Boolean get() = true

    override fun setSpeed(rate: Float) = exo.setPlaybackSpeed(rate)
}

/**
 * Pauses from the system media session that the display's mode switch sends rather than the viewer. Shield-class
 * boxes send one as HDMI renegotiates, which would leave a channel or movie paused after it starts, so such a pause
 * is ignored for a few seconds after [ignoreForModeSwitch]. Remote keys reach the player screens directly, so a real
 * press still pauses.
 */
internal class SystemPauseGuard {
    // Until when a pause from the system media session is ignored.
    @Volatile
    private var ignoredUntil = 0L

    /** The display is switching modes for the picture's frame rate. */
    fun ignoreForModeSwitch() {
        ignoredUntil = uptimeMs() + MODE_SWITCH_PAUSE_WINDOW_MS
    }

    /** Whether a media-session pause now is the display switching modes rather than the viewer. */
    fun ignoring(): Boolean = uptimeMs() < ignoredUntil
}

/**
 * Media3 refuses a second session under an id the process already holds, and each activity has its own player:
 * a sofaking://pair link opened from another app in split screen starts a second one beside the first.
 */
internal fun openExoSession(
    context: Context,
    forwarding: LivePlayerWrapper,
    onZapStep: (Int) -> Unit,
): MediaSessionHandle {
    forwarding.onZapStep = onZapStep
    val session = MediaSession.Builder(context, forwarding)
        .setId("player-${sessionIds.incrementAndGet()}")
        .setCallback(SessionAccess(context.packageName))
        .build()
    return object : MediaSessionHandle {
        override val platformSession: Any get() = session

        override fun publish(nowPlaying: NowPlaying) = forwarding.publishNowPlaying(nowPlaying.toMetadata())

        override fun close() {
            session.release()
            forwarding.onZapStep = {}
            forwarding.publishNowPlaying(MediaMetadata.EMPTY)
        }
    }
}

internal fun stateOf(state: Int) = when (state) {
    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
    Player.STATE_READY -> PlaybackState.READY
    Player.STATE_ENDED -> PlaybackState.ENDED
    else -> PlaybackState.IDLE
}

/** How long after a display mode switch a media-session pause is taken for HDMI renegotiating. */
private const val MODE_SWITCH_PAUSE_WINDOW_MS = 4_000L
