package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitView
import dev.jamesrich.sofaking.data.PlaybackProfile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerRateDidChangeNotification
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.errorLog
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.seekableTimeRanges
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSValue
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val player = remember(captionSync) { AvLivePlayer(captionSync) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The iOS player on AVPlayer. It plays HLS (AVFoundation has no raw MPEG-TS over HTTP) and draws the stream's
 * closed captions in the viewer's iOS caption style. Kotlin cannot override the KVO callback, so the state is read
 * on each command and each player notification (rate change, stall, failure, end), then every quarter second only
 * until it settles: playing with a frame on screen, paused, idle, failed or ended.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvLivePlayer(
    override val captionSync: CaptionSync,
    private val av: AVPlayer = AVPlayer(),
    private val tracks: AvTracks = AvTracks(av),
) : StreamLivePlayer(),
    PlayerTracks by tracks {
    private val playerLayer = AVPlayerLayer.playerLayerWithPlayer(av).apply {
        videoGravity =
            AVLayerVideoGravityResizeAspect
    }
    private val scope = MainScope()
    private var ended = false
    private var reportedFailure: AVPlayerItem? = null

    private val hasFrame = MutableStateFlow(false)
    private val mutableVideoSize = MutableStateFlow<IntSize?>(null)
    override val videoSize: StateFlow<IntSize?> = mutableVideoSize.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val observers: List<NSObjectProtocol> = listOf(
        AVPlayerItemDidPlayToEndTimeNotification,
        AVPlayerItemFailedToPlayToEndTimeNotification,
        AVPlayerItemPlaybackStalledNotification,
        AVPlayerRateDidChangeNotification,
    ).map { name ->
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { note ->
            if (note?.name == AVPlayerItemDidPlayToEndTimeNotification) ended = true
            refresh()
        }
    }

    init {
        scope.launch {
            for (signal in wake) {
                while (!publishStatus()) delay(STATUS_RETRY)
            }
        }
    }

    override val bufferedMs: Long get() = 0L
    override val supportsCaptionLead: Boolean get() = false

    /**
     * The audio session follows the volume, as audio focus does on Android: audible playback takes the Playback
     * category, which plays through the silent switch and interrupts other apps' sound, and the muted preview sits in
     * Ambient, which mixes with them.
     */
    override var volume: Float
        get() = av.volume
        set(value) {
            av.volume = value
            val audible = value > 0f
            if (audible == audibleSession) return
            audibleSession = audible
            val session = AVAudioSession.sharedInstance()
            if (audible) {
                session.setCategory(
                    AVAudioSessionCategoryPlayback,
                    mode = AVAudioSessionModeMoviePlayback,
                    options = 0u,
                    error = null,
                )
                session.setActive(true, error = null)
            } else {
                session.setCategory(
                    AVAudioSessionCategoryAmbient,
                    mode = AVAudioSessionModeDefault,
                    options = 0u,
                    error = null,
                )
            }
        }

    // Null until the first volume is set, so the first one always chooses the category.
    private var audibleSession: Boolean? = null

    override var playWhenReady: Boolean = true
        set(value) {
            field = value
            if (value) av.start(speed) else av.pause()
            refresh()
        }

    override fun prepare() {
        val url = stream?.url?.let(NSURL::URLWithString) ?: return
        ended = false
        av.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = url))
        if (playWhenReady) av.start(speed)
        refresh()
    }

    override fun stop() {
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        refresh()
    }

    /**
     * AVPlayer plays MP4 and its audio as is, and HEVC tagged `hvc1`, which the server copies into fragmented MP4 for
     * HLS. It shows HDR10 and HLG, tone-mapping them on an SDR screen, so it decodes HEVC Main 10.
     */
    override val playbackProfile = PlaybackProfile(
        containers = listOf("mp4"),
        videoCodecs = listOf("h264", "hevc"),
        audioCodecs = listOf("aac", "ac3", "eac3", "mp3", "alac", "flac"),
        maxHeight = 2160,
        hdr = true,
        deepColor = listOf("hevc"),
    )

    override val isSeekable: Boolean get() = stream?.seekable == true &&
        av.currentItem?.status == AVPlayerItemStatusReadyToPlay

    override val positionMs: Long
        get() = if (isSeekable) {
            (CMTimeGetSeconds(av.currentTime()) * 1000).takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0L)
                ?: 0L
        } else {
            0L
        }

    /**
     * A finished file reports its duration; a growing recording's is indefinite, so the end
     * of its seekable range stands in.
     */
    override val durationMs: Long?
        get() {
            val item = av.currentItem?.takeIf { isSeekable } ?: return null
            val seconds = CMTimeGetSeconds(item.duration).takeIf { it.isFinite() && it > 0 }
                ?: (item.seekableTimeRanges.lastOrNull() as? NSValue)?.CMTimeRangeValue?.useContents {
                    start.value.toDouble() / start.timescale + duration.value.toDouble() / duration.timescale
                }?.takeIf { it.isFinite() && it > 0 }
            return seconds?.let { (it * 1000).toLong() }
        }

    /** The end of the loaded range the picture is in, less the picture's time. */
    override val loadedAheadMs: Long?
        get() {
            if (stream?.seekable != false) return null
            val item = av.currentItem ?: return null
            val now = CMTimeGetSeconds(av.currentTime()).takeIf { it.isFinite() } ?: return null
            val end = item.loadedTimeRanges.mapNotNull { range ->
                (range as? NSValue)?.CMTimeRangeValue?.useContents {
                    val from = start.value.toDouble() / start.timescale
                    from to from + duration.value.toDouble() / duration.timescale
                }
            }.firstOrNull { (from, to) -> now >= from && now <= to }?.second ?: return null
            return ((end - now) * 1000).toLong().coerceAtLeast(0L)
        }

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        val target = clampSeek(positionMs, durationMs)
        av.seekToTime(CMTimeMakeWithSeconds(target.milliseconds.toDouble(DurationUnit.SECONDS), SEEK_TIMESCALE))
    }

    /** What [AVPlayer.start] plays at; AVPlayer's play() starts at normal speed, so the rate is set after it. */
    private var speed = 1f

    override val speedAdjustable: Boolean get() = true

    override fun setSpeed(rate: Float) {
        speed = rate
        if (av.rate != 0f) av.rate = rate
    }

    private val mediaSessions = NewestMediaSession { onZapStep -> openNowPlaying(this, scope, onZapStep) }

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = mediaSessions.open(onZapStep)

    @Composable
    override fun Video(modifier: Modifier, zoom: VideoZoom, shutter: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        val showsFrame by hasFrame.collectAsState()
        LaunchedEffect(zoom) { playerLayer.videoGravity = zoom.gravity }
        Box(
            modifier.onSizeChanged { size ->
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                playerLayer.frame =
                    CGRectMake(0.0, 0.0, size.width / density.toDouble(), size.height / density.toDouble())
                CATransaction.commit()
            },
        ) {
            UIKitView(
                factory = {
                    UIView().apply {
                        backgroundColor = UIColor.blackColor
                        clipsToBounds = true
                        layer.addSublayer(playerLayer)
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
            if (!showsFrame) shutter()
        }
    }

    override fun release() {
        observers.forEach(NSNotificationCenter.defaultCenter::removeObserver)
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        scope.cancel()
    }

    /** Publishes the state now and keeps reading it until it settles. */
    private fun refresh() {
        publishStatus()
        wake.trySend(Unit)
    }

    /** Hands [item] to the tracks once it is ready, and reports its failure once. */
    private fun readItem(item: AVPlayerItem) {
        tracks.ready(item)
        if (item.status == AVPlayerItemStatusFailed && reportedFailure != item) {
            reportedFailure = item
            val httpStatus = (item.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent)?.errorStatusCode
            mutableErrors.tryEmit(playerErrorFor(item.error, httpStatus))
        }
    }

    /** Publishes the state; true once it is settled and needs no further reads until something changes. */
    private fun publishStatus(): Boolean {
        val item = av.currentItem
        val playing = av.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val waiting = av.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        val state = stateOf(item, playing, ended, waiting)
        if (item == null) tracks.clear() else readItem(item)
        mutableStatus.value = PlayerStatus(state, playing, playWhenReady)
        // Like Android's shutter, it covers only from a reset until the first frame, not each rebuffer.
        val shown = item != null && playerLayer.readyForDisplay
        hasFrame.value = shown
        mutableVideoSize.value = item?.presentationSize?.useContents {
            if (width > 0 && height > 0) IntSize(width.roundToInt(), height.roundToInt()) else null
        }
        return when {
            item == null || ended || item.status == AVPlayerItemStatusFailed -> true
            playWhenReady -> playing && shown
            else -> item.status == AVPlayerItemStatusReadyToPlay && shown
        }
    }
}

/** What [item] is doing, as [AvLivePlayer] reads it; [waiting] is AVPlayer waiting to play at its rate. */
private fun stateOf(item: AVPlayerItem?, playing: Boolean, ended: Boolean, waiting: Boolean): PlaybackState = when {
    item == null -> PlaybackState.IDLE
    ended -> PlaybackState.ENDED
    item.status == AVPlayerItemStatusFailed -> PlaybackState.IDLE
    playing -> PlaybackState.READY
    waiting -> PlaybackState.BUFFERING
    item.status == AVPlayerItemStatusReadyToPlay -> PlaybackState.READY
    else -> PlaybackState.BUFFERING
}

/** Plays at [speed]; AVPlayer's play() starts at normal speed, so the rate is set after it. */
private fun AVPlayer.start(speed: Float) {
    play()
    if (speed != 1f) rate = speed
}

/**
 * The failure under Media3's code names, as the other players report it. The item's error log carries the HTTP
 * status of a failed segment or playlist; 410 is the relay closing the session.
 */
private fun playerErrorFor(error: NSError?, httpStatus: Long?): PlayerError {
    if (httpStatus != null && httpStatus >= FIRST_HTTP_ERROR) {
        return PlayerError(PlayerErrorCodes.BAD_HTTP_STATUS, streamGone = httpStatus == HTTP_GONE)
    }
    if (error == null) return PlayerError(PlayerErrorCodes.UNSPECIFIED)
    val code = when (error.domain) {
        NSURLErrorDomain -> if (error.code == NSURLErrorTimedOut) {
            PlayerErrorCodes.NETWORK_CONNECTION_TIMEOUT
        } else {
            PlayerErrorCodes.NETWORK_CONNECTION_FAILED
        }

        AVFoundationErrorDomain -> when (error.code) {
            AVErrorFileFormatNotRecognized, AVErrorFailedToParse -> PlayerErrorCodes.PARSING_CONTAINER_UNSUPPORTED
            AVErrorDecoderNotFound, AVErrorDecodeFailed -> PlayerErrorCodes.DECODING_FAILED
            else -> PlayerErrorCodes.UNSPECIFIED
        }

        else -> PlayerErrorCodes.UNSPECIFIED
    }
    return PlayerError(code)
}

/** AVFoundation's layer gravity for [VideoZoom]. */
private val VideoZoom.gravity: String?
    get() = when (this) {
        VideoZoom.FIT -> AVLayerVideoGravityResizeAspect
        VideoZoom.FILL -> AVLayerVideoGravityResizeAspectFill
        VideoZoom.STRETCH -> AVLayerVideoGravityResize
    }

/** How often a status the player could not publish yet is tried again. */
private val STATUS_RETRY = 250.milliseconds

/** The timescale of a seek's target: 600 ticks a second divides evenly by every common frame rate. */
private const val SEEK_TIMESCALE = 600

/** The first HTTP status that is an error, and the one the relay closes a session with. */
private const val FIRST_HTTP_ERROR = 400L
private const val HTTP_GONE = 410L
