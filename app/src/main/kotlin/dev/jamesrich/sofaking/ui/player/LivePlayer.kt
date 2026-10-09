package dev.jamesrich.sofaking.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Dispatcharr proxies streams as continuous MPEG-TS, which can start mid-GOP. With [captions] the player
 * declares the CEA-608/708 formats, renders text through [CaptionSync] and holds playback start for its
 * buffer; without it (the guide preview) no text track exists and the stock renderers and load control apply.
 */
@OptIn(UnstableApi::class)
internal fun buildLivePlayer(context: Context, captions: CaptionSync?, captionsOn: Boolean = false): ExoPlayer {
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
    val builder = if (captions == null) {
        ExoPlayer.Builder(context)
    } else {
        extractors.setTsSubtitleFormats(captionFormats())
        ExoPlayer.Builder(context, CaptionSyncRenderersFactory(context, captions))
            .setLoadControl(CaptionSyncLoadControl(captions))
            // The audible player yields to Assistant and other apps the way the MediaSession promises.
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
    }
    return builder
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            if (captions != null) trackSelectionParameters = captionParameters(trackSelectionParameters, captionsOn)
            playWhenReady = true
        }
}

/** Re-prepares at the live edge when the stream has run past the buffer; true when the error was handled. */
internal fun ExoPlayer.recoverIfBehindLive(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
    seekToDefaultPosition()
    prepare()
    return true
}
