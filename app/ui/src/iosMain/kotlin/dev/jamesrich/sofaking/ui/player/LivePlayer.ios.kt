package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitView
import dev.jamesrich.sofaking.data.PlaybackProfile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.*
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVPlayerItemAccessLogEvent
import platform.AVFoundation.accessLog
import platform.AVFoundation.presentationSize
import platform.AVFoundation.selectedMediaOptionInMediaSelectionGroup
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerRateDidChangeNotification
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.errorLog
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.seekToTime
import platform.AVFoundation.seekableTimeRanges
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSValue
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyIsLiveStream
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol

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
internal class AvLivePlayer(override val captionSync: CaptionSync) : StreamLivePlayer() {
    private val av = AVPlayer()
    private val playerLayer = AVPlayerLayer.playerLayerWithPlayer(av).apply { videoGravity = AVLayerVideoGravityResizeAspect }
    private val scope = MainScope()
    private var captionsOn = false
    private var ended = false
    private var reportedFailure: AVPlayerItem? = null
    // The item whose caption track was chosen, and its legible group. Both are read once the item is ready to play:
    // the asset has no group to report before then, and asking for it blocks the main thread while it loads.
    private var captionsAppliedTo: AVPlayerItem? = null
    private var legibleGroup: AVMediaSelectionGroup? = null
    private val hasFrame = MutableStateFlow(false)

    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    override val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val observers: List<NSObjectProtocol> = listOf(
        AVPlayerItemDidPlayToEndTimeNotification,
        AVPlayerItemFailedToPlayToEndTimeNotification,
        AVPlayerItemPlaybackStalledNotification,
        AVPlayerRateDidChangeNotification,
    ).map { name ->
        NSNotificationCenter.defaultCenter.addObserverForName(name = name, `object` = null, queue = NSOperationQueue.mainQueue) { note ->
            if (note?.name == AVPlayerItemDidPlayToEndTimeNotification) ended = true
            refresh()
        }
    }

    init {
        scope.launch {
            for (signal in wake) {
                while (!publishStatus()) delay(250)
            }
        }
    }

    override val bufferedMs: Long get() = 0L
    override val supportsCaptionLead: Boolean get() = false
    override val hasTextTrack: Boolean
        get() = av.currentItem?.takeIf { it === captionsAppliedTo } != null && legibleGroup?.options?.isNotEmpty() == true

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
                session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeMoviePlayback, options = 0u, error = null)
                session.setActive(true, error = null)
            } else {
                session.setCategory(AVAudioSessionCategoryAmbient, mode = AVAudioSessionModeDefault, options = 0u, error = null)
            }
        }

    // Null until the first volume is set, so the first one always chooses the category.
    private var audibleSession: Boolean? = null

    override var playWhenReady: Boolean = true
        set(value) {
            field = value
            if (value) av.play() else av.pause()
            refresh()
        }

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        applyCaptions()
    }

    override fun selectAudioTrack(id: String) {
        val item = av.currentItem ?: return
        val group = audibleGroup(item) ?: return
        val option = id.toIntOrNull()?.let { group.options.getOrNull(it) } as? AVMediaSelectionOption ?: return
        item.selectMediaOption(option, group)
        readAudioTracks(item)
    }

    /** AVFoundation reports the picture size and the stream's indicated bitrate; the codec and frame rate stay unknown. */
    override fun streamInfo(): StreamInfo {
        val item = av.currentItem ?: return StreamInfo()
        val (width, height) = item.presentationSize.useContents { width.toInt() to height.toInt() }
        val bitrate = (item.accessLog()?.events?.lastOrNull() as? AVPlayerItemAccessLogEvent)?.indicatedBitrate?.takeIf { it > 0 }?.toInt()
        return StreamInfo(width = width.takeIf { it > 0 }, height = height.takeIf { it > 0 }, bitrateBps = bitrate)
    }

    private fun audibleGroup(item: AVPlayerItem): AVMediaSelectionGroup? =
        item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)

    /** The item's audible options, numbered by position; one option is no choice. */
    private fun readAudioTracks(item: AVPlayerItem) {
        val group = audibleGroup(item)
        val options = group?.options.orEmpty().filterIsInstance<AVMediaSelectionOption>()
        val selected = group?.let { item.selectedMediaOptionInMediaSelectionGroup(it) }
        _audioTracks.value = if (options.size > 1) options.mapIndexed { index, option -> AudioTrack(index.toString(), option.displayName, option == selected) } else emptyList()
    }

    override fun prepare() {
        val url = stream?.url?.let(NSURL::URLWithString) ?: return
        ended = false
        av.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = url))
        if (playWhenReady) av.play()
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

    override val isSeekable: Boolean get() = stream?.seekable == true && av.currentItem?.status == AVPlayerItemStatusReadyToPlay

    override val positionMs: Long
        get() = if (isSeekable) (CMTimeGetSeconds(av.currentTime()) * 1000).takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0L) ?: 0L else 0L

    /** A finished file reports its duration; a growing recording's is indefinite, so the end of its seekable range stands in. */
    override val durationMs: Long?
        get() {
            val item = av.currentItem?.takeIf { isSeekable } ?: return null
            val seconds = CMTimeGetSeconds(item.duration).takeIf { it.isFinite() && it > 0 }
                ?: (item.seekableTimeRanges.lastOrNull() as? NSValue)?.CMTimeRangeValue?.useContents {
                    start.value.toDouble() / start.timescale + duration.value.toDouble() / duration.timescale
                }?.takeIf { it.isFinite() && it > 0 }
            return seconds?.let { (it * 1000).toLong() }
        }

    override fun seekTo(positionMs: Long) {
        if (!isSeekable) return
        val target = clampSeek(positionMs, durationMs)
        av.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600))
    }

    private val mediaSessions = NewestMediaSession(::openNowPlaying)

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle = mediaSessions.open(onZapStep)

    private fun openNowPlaying(onZapStep: (Int) -> Unit): MediaSessionHandle {
        val commands = MPRemoteCommandCenter.sharedCommandCenter()
        val next = commands.nextTrackCommand.addTargetWithHandler { _ ->
            onZapStep(1)
            MPRemoteCommandHandlerStatusSuccess
        }
        val previous = commands.previousTrackCommand.addTargetWithHandler { _ ->
            onZapStep(-1)
            MPRemoteCommandHandlerStatusSuccess
        }
        val playCommand = commands.playCommand.addTargetWithHandler { _ ->
            play()
            MPRemoteCommandHandlerStatusSuccess
        }
        val pauseCommand = commands.pauseCommand.addTargetWithHandler { _ ->
            pause()
            MPRemoteCommandHandlerStatusSuccess
        }
        return object : MediaSessionHandle {
            private var current: NowPlaying? = null

            override fun publish(nowPlaying: NowPlaying) {
                current = nowPlaying
                show(nowPlaying, artwork = null)
                // The lock screen needs the logo as an image; it replaces the text-only info once it arrives.
                val url = nowPlaying.artworkUrl?.let(NSURL::URLWithString) ?: return
                NSURLSession.sharedSession.dataTaskWithURL(url) { data: NSData?, _, _ ->
                    val image = data?.let { UIImage(data = it) } ?: return@dataTaskWithURL
                    scope.launch {
                        if (current !== nowPlaying) return@launch
                        show(nowPlaying, MPMediaItemArtwork(boundsSize = image.size) { _ -> image })
                    }
                }.resume()
            }

            private fun show(nowPlaying: NowPlaying, artwork: MPMediaItemArtwork?) {
                MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = buildMap<Any?, Any?> {
                    put(MPMediaItemPropertyTitle, nowPlaying.title)
                    put(MPMediaItemPropertyArtist, nowPlaying.subtitle)
                    put(MPNowPlayingInfoPropertyIsLiveStream, true)
                    if (artwork != null) put(MPMediaItemPropertyArtwork, artwork)
                }
            }

            override fun close() {
                current = null
                commands.nextTrackCommand.removeTarget(next)
                commands.previousTrackCommand.removeTarget(previous)
                commands.playCommand.removeTarget(playCommand)
                commands.pauseCommand.removeTarget(pauseCommand)
                MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            }
        }
    }

    @Composable
    override fun Video(modifier: Modifier, shutter: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        val showsFrame by hasFrame.collectAsState()
        Box(
            modifier.onSizeChanged { size ->
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                playerLayer.frame = CGRectMake(0.0, 0.0, size.width / density.toDouble(), size.height / density.toDouble())
                CATransaction.commit()
            },
        ) {
            UIKitView(
                factory = {
                    UIView().apply {
                        backgroundColor = UIColor.blackColor
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

    private fun applyCaptions() {
        val item = av.currentItem?.takeIf { it === captionsAppliedTo } ?: return
        val group = legibleGroup ?: return
        val option = if (captionsOn) group.options.firstOrNull() as? AVMediaSelectionOption else null
        item.selectMediaOption(option, group)
    }

    /** Publishes the state now and keeps reading it until it settles. */
    private fun refresh() {
        publishStatus()
        wake.trySend(Unit)
    }

    /** Publishes the state; true once it is settled and needs no further reads until something changes. */
    private fun publishStatus(): Boolean {
        val item = av.currentItem
        val playing = av.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val state = when {
            item == null -> PlaybackState.IDLE
            ended -> PlaybackState.ENDED
            item.status == AVPlayerItemStatusFailed -> PlaybackState.IDLE
            playing -> PlaybackState.READY
            av.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> PlaybackState.BUFFERING
            item.status == AVPlayerItemStatusReadyToPlay -> PlaybackState.READY
            else -> PlaybackState.BUFFERING
        }
        if (item != null && item.status == AVPlayerItemStatusReadyToPlay && captionsAppliedTo !== item) {
            captionsAppliedTo = item
            legibleGroup = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
            applyCaptions()
            readAudioTracks(item)
        }
        if (item == null) _audioTracks.value = emptyList()
        if (item != null && item.status == AVPlayerItemStatusFailed && reportedFailure != item) {
            reportedFailure = item
            val httpStatus = (item.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent)?.errorStatusCode
            _errors.tryEmit(playerErrorFor(item.error, httpStatus))
        }
        _status.value = PlayerStatus(state, playing, playWhenReady)
        // Like Android's shutter, it covers only from a reset until the first frame, not each rebuffer.
        val shown = item != null && playerLayer.readyForDisplay
        hasFrame.value = shown
        return when {
            item == null || ended || item.status == AVPlayerItemStatusFailed -> true
            playWhenReady -> playing && shown
            else -> item.status == AVPlayerItemStatusReadyToPlay && shown
        }
    }
}

/**
 * The failure under Media3's code names, as the other players report it. The item's error log carries the HTTP
 * status of a failed segment or playlist; 410 is the relay closing the session.
 */
private fun playerErrorFor(error: NSError?, httpStatus: Long?): PlayerError {
    if (httpStatus != null && httpStatus >= 400) return PlayerError(PlayerErrorCodes.BAD_HTTP_STATUS, streamGone = httpStatus == 410L)
    if (error == null) return PlayerError(PlayerErrorCodes.UNSPECIFIED)
    val code = when (error.domain) {
        NSURLErrorDomain -> if (error.code == NSURLErrorTimedOut) PlayerErrorCodes.NETWORK_CONNECTION_TIMEOUT else PlayerErrorCodes.NETWORK_CONNECTION_FAILED
        AVFoundationErrorDomain -> when (error.code) {
            AVErrorFileFormatNotRecognized, AVErrorFailedToParse -> PlayerErrorCodes.PARSING_CONTAINER_UNSUPPORTED
            AVErrorDecoderNotFound, AVErrorDecodeFailed -> PlayerErrorCodes.DECODING_FAILED
            else -> PlayerErrorCodes.UNSPECIFIED
        }
        else -> PlayerErrorCodes.UNSPECIFIED
    }
    return PlayerError(code)
}
