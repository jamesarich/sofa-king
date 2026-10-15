package dev.jamesrich.sofaking.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val exo: ExoPlayer = run {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setTsSubtitleFormats(captionFormats())
        ExoPlayer.Builder(context, CaptionSyncRenderersFactory(context, captionSync))
            .setLoadControl(CaptionSyncLoadControl(captionSync))
            // The audible player yields to Assistant and other apps the way the MediaSession promises.
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
            .build()
            .apply {
                trackSelectionParameters = captionParameters(trackSelectionParameters, enabled = false)
                playWhenReady = true
            }
    }

    private val pauseClock = LivePauseClock()
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

    override var volume: Float
        get() = exo.volume
        set(value) {
            exo.volume = value
        }

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

    override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
        exo.setMediaItem(mediaItemFor(stream, nowPlaying?.toMetadata() ?: MediaMetadata.EMPTY))
    }

    override fun replaceStream(stream: PlaybackStream) {
        val item = exo.currentMediaItem ?: return setStream(stream)
        exo.stop()
        exo.setMediaItem(item.buildUpon().setUri(stream.url).setMimeType(stream.mimeType).build())
    }

    override fun prepare() = exo.prepare()

    override fun stop() = exo.stop()

    override fun play() {
        if (pauseClock.onPlay(uptimeMs())) exo.seekToDefaultPosition()
        exo.play()
    }

    override fun pause() {
        pauseClock.onPause(uptimeMs())
        exo.pause()
    }

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle {
        val forwarding = LivePlayerWrapper(this, onZapStep)
        val session = MediaSession.Builder(context, forwarding).build()
        return object : MediaSessionHandle {
            override fun publish(nowPlaying: NowPlaying) = forwarding.publishNowPlaying(nowPlaying.toMetadata())

            override fun close() = session.release()
        }
    }

    /** Live video through media3-ui-compose: `ContentFrame` sizes a SurfaceView to the picture and shows [shutter] after each reset. */
    @Composable
    override fun Video(modifier: Modifier, shutter: @Composable () -> Unit) {
        ContentFrame(
            player = exo,
            modifier = modifier,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
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
