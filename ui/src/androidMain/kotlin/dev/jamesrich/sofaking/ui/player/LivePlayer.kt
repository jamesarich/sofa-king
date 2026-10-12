package dev.jamesrich.sofaking.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dev.jamesrich.sofaking.data.PlaybackStream
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * The app's one player, shared by the guide preview and the full screen so OK on the previewed channel
 * continues its stream. The server relays HLS or continuous MPEG-TS that can start mid-GOP; the CEA-608/708
 * formats are declared, text renders through [captions], and playback start waits for its buffer.
 * Captions start as [captionsOn]; switching text tracks mid-stream restarts a live stream, so the app
 * applies the setting once before the first prepare.
 */
@OptIn(UnstableApi::class)
internal fun buildLivePlayer(context: Context, captions: CaptionSync, captionsOn: Boolean = false): ExoPlayer {
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        .setTsSubtitleFormats(captionFormats())
    return ExoPlayer.Builder(context, CaptionSyncRenderersFactory(context, captions))
        .setLoadControl(CaptionSyncLoadControl(captions))
        // The audible player yields to Assistant and other apps the way the MediaSession promises.
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            trackSelectionParameters = captionParameters(trackSelectionParameters, captionsOn)
            playWhenReady = true
        }
}

/** The channel whose stream the player holds: items are tagged with the channel's guide id. */
internal val Player.currentChannelId: String?
    get() = currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }

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

/** The same item on the replacement [stream] the server published; metadata and channel tag carry over. */
internal fun MediaItem.withStream(stream: PlaybackStream): MediaItem =
    buildUpon().setUri(stream.url).setMimeType(stream.mimeType).build()

/**
 * True for HTTP 410 from the media relay: the stream behind the session changed epoch or the session is gone,
 * so the answer is to re-read the session and reopen rather than wait out the reconnect backoff.
 */
@OptIn(UnstableApi::class)
internal fun PlaybackException.isStreamGone(): Boolean =
    (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 410

/** Re-prepares at the live edge when the stream has run past the buffer; true when the error was handled. */
internal fun ExoPlayer.recoverIfBehindLive(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
    seekToDefaultPosition()
    prepare()
    return true
}
