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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToDate
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyIsLiveStream
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
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
 * closed captions in the viewer's iOS caption style. AVFoundation reports state through KVO; a quarter-second
 * poll on the main thread turns it into [status], which is all the screens need.
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

    private val _status = MutableStateFlow(PlayerStatus())
    private val _errors = MutableSharedFlow<PlayerError>(extraBufferCapacity = 8)
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()
    override val errors: SharedFlow<PlayerError> = _errors.asSharedFlow()
    override val cues: StateFlow<List<CaptionCue>> = MutableStateFlow(emptyList<CaptionCue>()).asStateFlow()

    private val endObserver: NSObjectProtocol = NSNotificationCenter.defaultCenter.addObserverForName(
        name = AVPlayerItemDidPlayToEndTimeNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> ended = true }

    init {
        scope.launch {
            while (isActive) {
                poll()
                delay(250)
            }
        }
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
            poll()
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
        poll()
    }

    override fun stop() {
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        poll()
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
            override fun publish(nowPlaying: NowPlaying) {
                MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = mapOf<Any?, Any?>(
                    MPMediaItemPropertyTitle to nowPlaying.title,
                    MPMediaItemPropertyArtist to nowPlaying.subtitle,
                    MPNowPlayingInfoPropertyIsLiveStream to true,
                )
            }

            override fun close() {
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
        val state by status.collectAsState()
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
            if (state.state != PlaybackState.READY) shutter()
        }
    }

    override fun release() {
        NSNotificationCenter.defaultCenter.removeObserver(endObserver)
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

    private fun poll() {
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
            _errors.tryEmit(PlayerError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        }
        _status.value = PlayerStatus(state, playing, playWhenReady)
    }
}
