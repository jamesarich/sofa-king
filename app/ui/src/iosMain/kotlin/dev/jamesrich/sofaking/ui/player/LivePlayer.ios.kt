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
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.errorLog
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToDate
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLSession
import platform.Foundation.addObserver
import platform.Foundation.dataTaskWithURL
import platform.Foundation.removeObserver
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
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import kotlin.time.Clock

@Composable
actual fun rememberLivePlayer(captionSync: CaptionSync): LivePlayer {
    val player = remember(captionSync) { AvLivePlayer(captionSync) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * The iOS player on AVPlayer. It plays HLS (AVFoundation has no raw MPEG-TS over HTTP) and draws the stream's
 * closed captions in the viewer's iOS caption style. AVFoundation reports state through KVO; each change is read
 * again on the main thread into [status], which is all the screens need.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvLivePlayer(override val captionSync: CaptionSync) : LivePlayer {
    private val av = AVPlayer()
    private val playerLayer = AVPlayerLayer.playerLayerWithPlayer(av).apply { videoGravity = AVLayerVideoGravityResizeAspect }
    private val scope = MainScope()
    private val pauseClock = LivePauseClock()
    private var stream: PlaybackStream? = null
    private var captionsOn = false
    private var ended = false
    private var reportedFailure: AVPlayerItem? = null
    private val hasFrame = MutableStateFlow(false)

    private val _status = MutableStateFlow(PlayerStatus())
    private val _errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()
    override val errors: SharedFlow<PlayerError> = _errors.asSharedFlow()
    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()

    private val endObserver: NSObjectProtocol = NSNotificationCenter.defaultCenter.addObserverForName(
        name = AVPlayerItemDidPlayToEndTimeNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        ended = true
        publishStatus()
    }

    /** KVO may call from any thread; the state is read again on the main thread. */
    private val observer = KeyValueObserver { scope.launch { publishStatus() } }

    init {
        // A key path through currentItem follows each new item without re-registering.
        for (key in PLAYER_KEYS) av.addObserver(observer, forKeyPath = key, options = NSKeyValueObservingOptionNew, context = null)
        playerLayer.addObserver(observer, forKeyPath = READY_FOR_DISPLAY, options = NSKeyValueObservingOptionNew, context = null)
    }

    override val currentChannelId: String? get() = stream?.channelId
    override val currentStreamUrl: String? get() = stream?.url
    override val hasItem: Boolean get() = stream != null
    override val bufferedMs: Long get() = 0L
    override val hasTextTrack: Boolean
        get() = av.currentItem?.asset?.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)?.options?.isNotEmpty() == true

    override var volume: Float
        get() = av.volume
        set(value) {
            av.volume = value
        }

    override var playWhenReady: Boolean = true
        set(value) {
            field = value
            if (value) av.play() else av.pause()
            publishStatus()
        }

    override fun setCaptionsEnabled(enabled: Boolean) {
        captionsOn = enabled
        applyCaptions()
    }

    override fun setStream(stream: PlaybackStream, nowPlaying: NowPlaying?) {
        this.stream = stream
    }

    override fun replaceStream(stream: PlaybackStream) {
        stop()
        this.stream = stream
    }

    override fun prepare() {
        val url = stream?.url?.let(NSURL::URLWithString) ?: return
        ended = false
        av.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = url))
        applyCaptions()
        if (playWhenReady) av.play()
        publishStatus()
    }

    override fun stop() {
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        publishStatus()
    }

    override fun play() {
        if (pauseClock.onPlay(Clock.System.now().toEpochMilliseconds())) av.currentItem?.seekToDate(NSDate())
        playWhenReady = true
    }

    override fun pause() {
        pauseClock.onPause(Clock.System.now().toEpochMilliseconds())
        playWhenReady = false
    }

    override fun openMediaSession(onZapStep: (Int) -> Unit): MediaSessionHandle {
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
        NSNotificationCenter.defaultCenter.removeObserver(endObserver)
        for (key in PLAYER_KEYS) av.removeObserver(observer, forKeyPath = key)
        playerLayer.removeObserver(observer, forKeyPath = READY_FOR_DISPLAY)
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        scope.cancel()
    }

    private fun applyCaptions() {
        val item = av.currentItem ?: return
        val group = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible) ?: return
        val option = if (captionsOn) group.options.firstOrNull() as? AVMediaSelectionOption else null
        item.selectMediaOption(option, group)
    }

    private fun publishStatus() {
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
        if (item != null && item.status == AVPlayerItemStatusFailed && reportedFailure != item) {
            reportedFailure = item
            val httpStatus = (item.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent)?.errorStatusCode
            _errors.tryEmit(playerErrorFor(item.error, httpStatus))
        }
        _status.value = PlayerStatus(state, playing, playWhenReady)
        // Like Android's shutter, it covers only from a reset until the first frame, not each rebuffer.
        hasFrame.value = item != null && playerLayer.readyForDisplay
    }
}

private val PLAYER_KEYS = listOf("timeControlStatus", "currentItem.status")
private const val READY_FOR_DISPLAY = "readyForDisplay"

/** Hands every KVO change to [onChange]; what changed is read from the player itself. */
@OptIn(ExperimentalForeignApi::class)
private class KeyValueObserver(private val onChange: () -> Unit) : NSObject(), NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(keyPath: String?, ofObject: Any?, change: Map<Any?, *>?, context: COpaquePointer?) {
        onChange()
    }
}

/**
 * The failure under Media3's code names, as the other players report it. The item's error log carries the HTTP
 * status of a failed segment or playlist; 410 is the relay closing the session.
 */
private fun playerErrorFor(error: NSError?, httpStatus: Long?): PlayerError {
    if (httpStatus != null && httpStatus >= 400) return PlayerError("ERROR_CODE_IO_BAD_HTTP_STATUS", streamGone = httpStatus == 410L)
    val code = when (error?.domain) {
        NSURLErrorDomain -> if (error.code == NSURLErrorTimedOut) "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" else "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
        AVFoundationErrorDomain -> when (error.code) {
            AVErrorFileFormatNotRecognized, AVErrorFailedToParse -> "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
            AVErrorDecoderNotFound, AVErrorDecodeFailed -> "ERROR_CODE_DECODING_FAILED"
            else -> "ERROR_CODE_UNSPECIFIED"
        }
        else -> "ERROR_CODE_UNSPECIFIED"
    }
    return PlayerError(code)
}
