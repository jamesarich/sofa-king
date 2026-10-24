package dev.jamesrich.sofaking.ui.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.Foundation.NSData
import platform.Foundation.NSURL
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
import platform.UIKit.UIImage

/**
 * The lock screen's and Control Center's Now Playing for [player]: their play and pause reach it, and next and
 * previous zap through [onZapStep]; artwork loads in [scope].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun openNowPlaying(player: LivePlayer, scope: CoroutineScope, onZapStep: (Int) -> Unit): MediaSessionHandle {
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
        player.play()
        MPRemoteCommandHandlerStatusSuccess
    }
    val pauseCommand = commands.pauseCommand.addTargetWithHandler { _ ->
        player.pause()
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
