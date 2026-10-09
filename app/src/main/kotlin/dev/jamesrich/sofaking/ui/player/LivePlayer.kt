package dev.jamesrich.sofaking.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** Dispatcharr proxies streams as continuous MPEG-TS, which can start mid-GOP. */
@OptIn(UnstableApi::class)
internal fun buildLivePlayer(context: Context, captionsOn: Boolean, sync: CaptionSync): ExoPlayer {
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        .setTsSubtitleFormats(captionFormats())
    return ExoPlayer.Builder(context, CaptionSyncRenderersFactory(context, sync))
        .setLoadControl(CaptionSyncLoadControl(sync))
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
        .build()
        .apply {
            trackSelectionParameters = captionParameters(trackSelectionParameters, captionsOn)
            playWhenReady = true
        }
}
