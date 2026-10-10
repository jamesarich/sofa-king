package dev.jamesrich.sofaking.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * The app's one player, shared by the guide preview and the full screen so OK on the previewed channel
 * continues its stream. Dispatcharr proxies continuous MPEG-TS that can start mid-GOP; the CEA-608/708
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
        // Backstop behind StallWatch: buffering this long with nothing loading becomes an error the player retries.
        .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_MS)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            trackSelectionParameters = captionParameters(trackSelectionParameters, captionsOn)
            playWhenReady = true
        }
}

private const val STUCK_BUFFERING_MS = 15_000

/** The stream URL of the item the player holds, if any. */
internal val Player.currentStreamUrl: String?
    get() = currentMediaItem?.localConfiguration?.uri?.toString()

/** Re-prepares at the live edge when the stream has run past the buffer; true when the error was handled. */
internal fun ExoPlayer.recoverIfBehindLive(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
    seekToDefaultPosition()
    prepare()
    return true
}
